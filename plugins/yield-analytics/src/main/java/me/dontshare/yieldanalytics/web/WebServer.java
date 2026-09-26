package me.dontshare.yieldanalytics.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.dontshare.yieldanalytics.AnalyticsConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The analytics website: the dashboard's static files at {@code /}, and the
 * JSON API it reads at {@code /api/*}.
 * <p>
 * Runs on its own small thread pool, never the main thread - every API
 * answer comes from snapshots the game thread already built, or straight
 * from the database. Every API call but {@code /api/health} goes through
 * {@link Access}: whitelisted addresses may read and edit, anyone else needs
 * the current viewer code (a bearer header or {@code ?token=}) and may only
 * read. An address that keeps getting the code wrong is turned away for a
 * while.
 * <p>
 * Edits are POSTs with a JSON body and an {@code X-Yield-Request} header,
 * from this site's own origin only. A whitelisted address needs no code, so
 * without those checks any other website its owner visited could make their
 * browser post edits on their behalf.
 */
public final class WebServer {

    private static final Gson GSON = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().create();
    private static final int MAX_FAILED_AUTH_PER_MINUTE = 10;
    private static final int MAX_EDITS_PER_MINUTE = 60;
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Map<String, String> STATIC = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/app.js", "app.js",
            "/style.css", "style.css",
            "/favicon.svg", "favicon.svg");

    private final AnalyticsConfig config;
    private final ApiRoutes routes;
    private final Logger logger;
    private final Access access;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final Map<String, Failures> edits = new ConcurrentHashMap<>();
    private final Map<String, byte[]> staticCache = new HashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private HttpServer server;
    private ExecutorService pool;

    private static final class Failures {
        volatile long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger();
    }

    public WebServer(AnalyticsConfig config, ApiRoutes routes, Access access, Logger logger) {
        this.config = config;
        this.routes = routes;
        this.access = access;
        this.logger = logger;
    }

    public long requestsServed() {
        return requests.get();
    }

    public boolean running() {
        return server != null;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 64);
        AtomicInteger threadNumber = new AtomicInteger();
        pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "yield-analytics-web-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            securityHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                cors(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !(method.equals("POST") && path.startsWith("/api/"))) {
                send(exchange, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/api/")) {
                api(exchange, path);
            } else {
                staticFile(exchange, path);
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Analytics web request failed", e);
        }
    }

    private void api(HttpExchange exchange, String path) throws IOException {
        cors(exchange);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (path.equals("/api/health")) {
            json(exchange, 200, Map.of("ok", true));
            return;
        }
        String address = access.clientIp(exchange.getRemoteAddress().getAddress().getHostAddress(),
                exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
        Failures failed = failures.get(address);
        if (failed != null && System.currentTimeMillis() - failed.windowStart < 60_000L
                && failed.count.get() >= MAX_FAILED_AUTH_PER_MINUTE) {
            json(exchange, 429, Map.of("error", "Too many attempts - try again in a minute."));
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        Access.Caller caller = access.identify(address, exchange.getRequestHeaders().getFirst("X-Forwarded-For"), givenCode(exchange, query));
        if (caller.role() == Access.Role.EDITOR && !ownHost(exchange.getRequestHeaders().getFirst("Host"))) {
            // Opened under someone else's domain: no whitelist privileges.
            caller = access.identifyByCode(address, givenCode(exchange, query));
        }
        if (caller.role() == Access.Role.NONE) {
            if (failures.size() > 1000) {
                long cutoff = System.currentTimeMillis() - 60_000L;
                failures.values().removeIf(stale -> stale.windowStart < cutoff);
            }
            if (givenCode(exchange, query) != null) {
                count(failures, address);
            }
            json(exchange, 401, Map.of("error", givenCode(exchange, query) == null
                    ? "Enter the viewer code." : "That code has expired or is wrong - ask for the current one.",
                    "ip", caller.ip()));
            return;
        }
        String route = path.substring("/api/".length());
        boolean post = "POST".equalsIgnoreCase(exchange.getRequestMethod());
        try {
            Object body;
            if (post) {
                String refusal = editRefusal(exchange, caller);
                if (refusal != null) {
                    json(exchange, 403, Map.of("error", refusal));
                    return;
                }
                if (count(edits, caller.ip()) > MAX_EDITS_PER_MINUTE) {
                    json(exchange, 429, Map.of("error", "That's a lot of edits - wait a minute."));
                    return;
                }
                body = routes.post(route, readBody(exchange), caller);
            } else {
                body = routes.get(route, query, caller);
            }
            if (body == null) {
                json(exchange, 404, Map.of("error", "No such endpoint or record."));
            } else {
                json(exchange, 200, body);
            }
        } catch (java.util.NoSuchElementException e) {
            json(exchange, 404, Map.of("error", String.valueOf(e.getMessage())));
        } catch (IllegalArgumentException e) {
            json(exchange, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (IllegalStateException e) {
            json(exchange, 409, Map.of("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Analytics API " + path + " failed", e);
            json(exchange, 500, Map.of("error", "Server error - see the console."));
        }
    }

    /**
     * Whether the site was opened by an address or one of its own names.
     * Anything else is another site's domain pointed here, and whitelisted
     * access isn't lent to it.
     */
    private boolean ownHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            return true; // [IPv6]:port
        }
        int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        if (host.equals("localhost") || host.matches("[0-9.]+")) {
            return true;
        }
        for (String name : config.hostnames()) {
            if (host.equals(name.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Why this POST may not edit, or null if it may. */
    private String editRefusal(HttpExchange exchange, Access.Caller caller) {
        if (caller.role() != Access.Role.EDITOR) {
            return "Only whitelisted addresses can make changes.";
        }
        var headers = exchange.getRequestHeaders();
        if (!"1".equals(headers.getFirst("X-Yield-Request"))) {
            return "Edits must come from the dashboard.";
        }
        String type = headers.getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            return "Edits must be JSON.";
        }
        String origin = headers.getFirst("Origin");
        String host = headers.getFirst("Host");
        if (origin != null && !origin.equals("http://" + host) && !origin.equals("https://" + host)
                && !config.allowedOrigins().contains(origin)) {
            return "Edits must come from the dashboard's own site.";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Request too large.");
        }
        try {
            Map<String, Object> body = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), Map.class);
            return body != null ? body : Map.of();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not valid JSON.");
        }
    }

    /** Counts one event for {@code key} in the current minute, returning the count so far. */
    private static int count(Map<String, Failures> counters, String key) {
        Failures entry = counters.computeIfAbsent(key, ignored -> new Failures());
        if (System.currentTimeMillis() - entry.windowStart >= 60_000L) {
            entry.windowStart = System.currentTimeMillis();
            entry.count.set(0);
        }
        return entry.count.incrementAndGet();
    }

    private static String givenCode(HttpExchange exchange, Map<String, String> query) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = header.substring(7).trim();
            return value.isEmpty() ? null : value;
        }
        String token = query.get("token");
        return token == null || token.isBlank() ? null : token;
    }

    private void cors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || config.allowedOrigins().isEmpty()) {
            return;
        }
        if (config.allowedOrigins().contains("*") || config.allowedOrigins().contains(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", config.allowedOrigins().contains("*") ? "*" : origin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Yield-Request");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Vary", "Origin");
        }
    }

    private static void securityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
    }

    private void staticFile(HttpExchange exchange, String path) throws IOException {
        String file = STATIC.get(path);
        if (file == null) {
            send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = load(file);
        if (body == null) {
            send(exchange, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String type = file.endsWith(".html") ? "text/html; charset=utf-8"
                : file.endsWith(".js") ? "application/javascript; charset=utf-8"
                : file.endsWith(".css") ? "text/css; charset=utf-8"
                : file.endsWith(".svg") ? "image/svg+xml" : "application/octet-stream";
        if (file.endsWith(".html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; "
                            + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "
                            + "img-src 'self' data: https://mc-heads.net https://assets.mcasset.cloud; connect-src 'self'; frame-ancestors 'none'");
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        send(exchange, 200, type, body);
    }

    private synchronized byte[] load(String file) throws IOException {
        byte[] cached = staticCache.get(file);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + file)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            staticCache.put(file, bytes);
            return bytes;
        }
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> params = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(key.toLowerCase(Locale.ROOT), value);
        }
        return params;
    }
}
