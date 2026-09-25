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
import java.security.MessageDigest;
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
 * from the database. Every API call but {@code /api/health} needs the token
 * from analytics.yml, as a bearer header or {@code ?token=}; an address that
 * keeps getting it wrong is turned away for a while.
 */
public final class WebServer {

    private static final Gson GSON = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().create();
    private static final int MAX_FAILED_AUTH_PER_MINUTE = 10;
    private static final Map<String, String> STATIC = Map.of(
            "/", "index.html",
            "/index.html", "index.html",
            "/app.js", "app.js",
            "/style.css", "style.css",
            "/favicon.svg", "favicon.svg");

    private final AnalyticsConfig config;
    private final ApiRoutes routes;
    private final Logger logger;
    private final byte[] token;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final Map<String, byte[]> staticCache = new HashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private HttpServer server;
    private ExecutorService pool;

    private static final class Failures {
        volatile long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger();
    }

    public WebServer(AnalyticsConfig config, ApiRoutes routes, Logger logger) {
        this.config = config;
        this.routes = routes;
        this.logger = logger;
        this.token = config.token().getBytes(StandardCharsets.UTF_8);
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
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
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
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        Failures failed = failures.get(address);
        if (failed != null && System.currentTimeMillis() - failed.windowStart < 60_000L
                && failed.count.get() >= MAX_FAILED_AUTH_PER_MINUTE) {
            json(exchange, 429, Map.of("error", "Too many attempts - try again in a minute."));
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        if (!authorized(exchange, query)) {
            if (failures.size() > 1000) {
                long cutoff = System.currentTimeMillis() - 60_000L;
                failures.values().removeIf(stale -> stale.windowStart < cutoff);
            }
            Failures entry = failures.computeIfAbsent(address, key -> new Failures());
            if (System.currentTimeMillis() - entry.windowStart >= 60_000L) {
                entry.windowStart = System.currentTimeMillis();
                entry.count.set(0);
            }
            entry.count.incrementAndGet();
            json(exchange, 401, Map.of("error", "Missing or wrong token."));
            return;
        }
        try {
            Object body = routes.route(path.substring("/api/".length()), query);
            if (body == null) {
                json(exchange, 404, Map.of("error", "No such endpoint or record."));
            } else {
                json(exchange, 200, body);
            }
        } catch (IllegalArgumentException e) {
            json(exchange, 400, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Analytics API " + path + " failed", e);
            json(exchange, 500, Map.of("error", "Server error - see the console."));
        }
    }

    private boolean authorized(HttpExchange exchange, Map<String, String> query) {
        String given = null;
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            given = header.substring(7).trim();
        } else if (query.containsKey("token")) {
            given = query.get("token");
        }
        // Constant-time, so response timing can't be used to guess it.
        return given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }

    private void cors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || config.allowedOrigins().isEmpty()) {
            return;
        }
        if (config.allowedOrigins().contains("*") || config.allowedOrigins().contains(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", config.allowedOrigins().contains("*") ? "*" : origin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
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
                            + "img-src 'self' data: https://mc-heads.net; connect-src 'self'; frame-ancestors 'none'");
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
