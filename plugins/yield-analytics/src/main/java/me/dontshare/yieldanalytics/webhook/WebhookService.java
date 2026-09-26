package me.dontshare.yieldanalytics.webhook;

import com.google.gson.Gson;
import me.dontshare.yieldanalytics.AnalyticsConfig;
import me.dontshare.yieldanalytics.AnalyticsConfig.WebhookTarget;
import me.dontshare.yieldcore.diagnostics.Diagnostics;
import me.dontshare.yieldcore.status.ServerHealth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Posts what happens on the server to the webhooks in analytics.yml -
 * Discord (as embeds) or any HTTP endpoint (as JSON).
 * <p>
 * Events: a periodic {@code summary}; {@code alert} when TPS or tick time
 * stays past a threshold; {@code peak} for a new all-time online record;
 * {@code error} for SEVERE log lines; {@code server} on start and stop; and
 * {@code player} for brand-new players, batched to one post a minute so a
 * launch rush doesn't trip Discord's rate limit.
 * <p>
 * Sending never blocks the main thread - except the stop notice, which has
 * to leave before the process does.
 */
public final class WebhookService {

    private static final Gson GSON = new Gson();
    private static final int DISCORD_BLUE = 0x4BD9FF;
    private static final int DISCORD_GREEN = 0x55FF7F;
    private static final int DISCORD_ORANGE = 0xFF9F43;
    private static final int DISCORD_RED = 0xFF5555;
    private static final int DISCORD_GOLD = 0xFFC83D;

    private final AnalyticsConfig config;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final List<String> pendingNewPlayers = new CopyOnWriteArrayList<>();
    private final Map<String, Long> lastAlertAt = new ConcurrentHashMap<>();
    private long lowTpsSince;
    private long highMsptSince;
    private double periodMinTps = 20;
    private double periodMaxMspt;
    private int periodPeakOnline;
    private long lastSendFailureLog;

    public WebhookService(AnalyticsConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public int targetCount() {
        return config.webhooks().size();
    }

    private boolean anyWants(String event) {
        for (WebhookTarget target : config.webhooks()) {
            if (target.wants(event)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ events

    public void serverStarted(int zones, int plugins) {
        post("server", "Server started", "The server is up and taking players.", DISCORD_GREEN,
                fields("Zones", String.valueOf(zones), "Yield plugins", String.valueOf(plugins)), false);
    }

    /** Blocks briefly - the process is about to exit. */
    public void serverStopping(int online) {
        post("server", "Server stopping", "The server is shutting down.", DISCORD_ORANGE,
                fields("Online at stop", String.valueOf(online)), true);
    }

    public void newPlayer(String name) {
        if (anyWants("player")) {
            pendingNewPlayers.add(name);
        }
    }

    /** Once a minute: everyone new since the last post, in one message. */
    public void flushNewPlayers() {
        if (pendingNewPlayers.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>(pendingNewPlayers);
        pendingNewPlayers.removeAll(names);
        String list = String.join(", ", names.subList(0, Math.min(40, names.size())))
                + (names.size() > 40 ? " and " + (names.size() - 40) + " more" : "");
        post("player", names.size() == 1 ? "New player" : names.size() + " new players",
                list, DISCORD_BLUE, Map.of(), false);
    }

    public void newPeak(int now, int previous) {
        post("peak", "New all-time peak: " + now + " online",
                "The previous record was " + previous + ".", DISCORD_GOLD,
                fields("Online", String.valueOf(now), "Previous peak", String.valueOf(previous)), false);
    }

    /** Someone changed a player on the site. */
    public void edit(String actor, String player, String summary) {
        post("edit", actor + " edited " + player, summary, DISCORD_BLUE, Map.of(), false);
    }

    public void error(String message) {
        post("error", "Server error", "```\n" + truncate(message, 1800) + "\n```", DISCORD_RED, Map.of(), false);
    }

    /** Main thread, every couple of seconds. Alerts once a threshold has held for long enough. */
    public void checkHealth(ServerHealth health, int online) {
        long now = System.currentTimeMillis();
        periodMinTps = Math.min(periodMinTps, health.tps1m());
        periodMaxMspt = Math.max(periodMaxMspt, health.msptAvg());
        periodPeakOnline = Math.max(periodPeakOnline, online);
        long sustained = config.sustainedSeconds() * 1000L;

        if (health.tps1m() < config.tpsBelow()) {
            lowTpsSince = lowTpsSince == 0 ? now : lowTpsSince;
            if (now - lowTpsSince >= sustained) {
                alert("tps", String.format(Locale.ROOT, "TPS has been below %.1f for %ds", config.tpsBelow(), (now - lowTpsSince) / 1000),
                        health, online);
            }
        } else {
            lowTpsSince = 0;
        }
        if (health.msptAvg() > config.msptAbove()) {
            highMsptSince = highMsptSince == 0 ? now : highMsptSince;
            if (now - highMsptSince >= sustained) {
                alert("mspt", String.format(Locale.ROOT, "Tick time has been above %.0f ms for %ds", config.msptAbove(), (now - highMsptSince) / 1000),
                        health, online);
            }
        } else {
            highMsptSince = 0;
        }
    }

    private void alert(String kind, String message, ServerHealth health, int online) {
        long now = System.currentTimeMillis();
        Long last = lastAlertAt.get(kind);
        if (last != null && now - last < config.alertCooldownMinutes() * 60_000L) {
            return;
        }
        lastAlertAt.put(kind, now);
        post("alert", "Performance alert", message, DISCORD_RED, fields(
                "TPS (1m/5m)", String.format(Locale.ROOT, "%.1f / %.1f", health.tps1m(), health.tps5m()),
                "MSPT avg / p95", String.format(Locale.ROOT, "%.1f / %.1f ms", health.msptAvg(), health.msptP95()),
                "Online", String.valueOf(online),
                "Memory", health.heapUsedMb() + " / " + health.heapMaxMb() + " MB",
                "DB queue", String.valueOf(health.dbQueued())), false);
    }

    /** Findings that were there last time - an alert goes out when one first appears. */
    private Set<String> activeFindings = Set.of();

    /**
     * Main thread, every few seconds: posts each new warning or critical
     * finding from the server's self-checks once, then again only if it
     * clears and comes back after the cooldown.
     */
    public void healthFindings(List<Diagnostics.Finding> findings) {
        Set<String> now = new HashSet<>();
        for (Diagnostics.Finding finding : findings) {
            if (finding.severity() == Diagnostics.Severity.INFO) {
                continue;
            }
            now.add(finding.key());
            if (activeFindings.contains(finding.key())) {
                continue;
            }
            long at = System.currentTimeMillis();
            Long last = lastAlertAt.get("health:" + finding.key());
            if (last != null && at - last < config.alertCooldownMinutes() * 60_000L) {
                continue;
            }
            lastAlertAt.put("health:" + finding.key(), at);
            boolean critical = finding.severity() == Diagnostics.Severity.CRITICAL;
            post("alert", (critical ? "Critical: " : "Warning: ") + finding.title(), finding.detail(),
                    critical ? DISCORD_RED : DISCORD_GOLD, Map.of(), false);
        }
        activeFindings = now;
    }

    /** The periodic recap: what happened since the last one. */
    public void summary(Map<String, Long> counts, int onlineNow, int totalPlayers) {
        if (!anyWants("summary")) {
            resetPeriod(onlineNow);
            return;
        }
        long playMs = counts.getOrDefault("playtimeMs", 0L);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Online now", String.valueOf(onlineNow));
        fields.put("Peak this period", String.valueOf(Math.max(periodPeakOnline, onlineNow)));
        fields.put("New players", String.valueOf(counts.getOrDefault("newPlayers", 0L)));
        fields.put("Joins", String.valueOf(counts.getOrDefault("joins", 0L)));
        fields.put("Hours played", String.format(Locale.ROOT, "%.1f", playMs / 3_600_000.0));
        fields.put("Cubes broken", String.valueOf(counts.getOrDefault("cubeKills", 0L)));
        fields.put("Eggs hatched", String.valueOf(counts.getOrDefault("eggsHatched", 0L)));
        fields.put("Rebirths", String.valueOf(counts.getOrDefault("rebirths", 0L)));
        fields.put("Zones unlocked", String.valueOf(counts.getOrDefault("zoneUnlocks", 0L)));
        fields.put("Tutorials finished", counts.getOrDefault("tutorialCompleted", 0L) + " of "
                + counts.getOrDefault("tutorialStarted", 0L) + " started");
        fields.put("Lowest TPS", String.format(Locale.ROOT, "%.1f", periodMinTps));
        fields.put("Worst avg tick", String.format(Locale.ROOT, "%.1f ms", periodMaxMspt));
        fields.put("Players all-time", String.valueOf(totalPlayers));
        post("summary", "Server summary", "The last " + config.summaryIntervalMinutes() + " minutes.", DISCORD_BLUE, fields, false);
        resetPeriod(onlineNow);
    }

    private void resetPeriod(int onlineNow) {
        periodMinTps = 20;
        periodMaxMspt = 0;
        periodPeakOnline = onlineNow;
    }

    /** A test post to every target, whatever it listens for. */
    public int test(String by) {
        int sent = 0;
        for (WebhookTarget target : config.webhooks()) {
            send(target, "test", "Webhook test", "Sent by " + by + " - this target is wired up.", DISCORD_GREEN, Map.of(), false);
            sent++;
        }
        return sent;
    }

    // ------------------------------------------------------------ sending

    private void post(String event, String title, String description, int color, Map<String, String> fields, boolean blocking) {
        for (WebhookTarget target : config.webhooks()) {
            if (target.wants(event)) {
                send(target, event, title, description, color, fields, blocking);
            }
        }
    }

    private void send(WebhookTarget target, String event, String title, String description, int color,
                      Map<String, String> fields, boolean blocking) {
        String body = target.format() == WebhookTarget.Format.DISCORD
                ? discordBody(title, description, color, fields)
                : jsonBody(event, title, description, fields);
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.url()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "yield-analytics")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        if (blocking) {
            try {
                http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                logFailure(target, e.toString());
            }
            return;
        }
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, error) -> {
            if (error != null) {
                logFailure(target, error.toString());
            } else if (response.statusCode() >= 300) {
                logFailure(target, "HTTP " + response.statusCode());
            }
        });
    }

    /** WARNING, never SEVERE - a failed error post must not trigger another error post. */
    private void logFailure(WebhookTarget target, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastSendFailureLog > 60_000L) {
            lastSendFailureLog = now;
            logger.log(Level.WARNING, "Webhook '" + target.name() + "' failed: " + reason);
        }
    }

    private static String discordBody(String title, String description, int color, Map<String, String> fields) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", truncate(title, 250));
        embed.put("description", truncate(description, 4000));
        embed.put("color", color);
        List<Map<String, Object>> fieldList = new ArrayList<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", truncate(field.getKey(), 250));
            entry.put("value", truncate(field.getValue(), 1000));
            entry.put("inline", true);
            fieldList.add(entry);
            if (fieldList.size() == 25) {
                break;
            }
        }
        embed.put("fields", fieldList);
        embed.put("timestamp", Instant.now().toString());
        embed.put("footer", Map.of("text", "Yield analytics"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", "Yield Analytics");
        payload.put("embeds", List.of(embed));
        return GSON.toJson(payload);
    }

    private static String jsonBody(String event, String title, String description, Map<String, String> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("title", title);
        payload.put("description", description);
        payload.put("fields", fields);
        payload.put("timestamp", System.currentTimeMillis());
        return GSON.toJson(payload);
    }

    private static Map<String, String> fields(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
