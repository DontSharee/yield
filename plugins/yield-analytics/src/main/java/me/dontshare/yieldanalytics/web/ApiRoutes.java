package me.dontshare.yieldanalytics.web;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import me.dontshare.yieldanalytics.collect.LiveMonitor;
import me.dontshare.yieldanalytics.collect.StatsJob;
import me.dontshare.yieldanalytics.data.ActivityStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.ItemDefinition;
import org.bson.Document;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The JSON API behind the dashboard. Every answer is built from the live
 * snapshot, the stats snapshot, or a direct database read - never game
 * state, which only the main thread may touch.
 * <pre>
 *   overview            headline numbers, today so far, the last day of online players
 *   live                every zone and who is in it, right now
 *   stats               averages and distributions over every player, pets, zones, leaders
 *   tutorial            the tutorial funnel
 *   activity?hours=48   hourly counters (kills, hatches, joins...) over time
 *   retention           daily actives, newcomers, cohort retention
 *   performance?hours=6 tick time, TPS, memory and traffic over time, plus per-system costs now
 *   diagnostics         the server's self-checks: lag spikes and their causes, memory trend, leaks, failed saves
 *   players?q=abc       player search by name
 *   player?name=abc     one player's full picture
 * </pre>
 */
public final class ApiRoutes {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final LiveMonitor live;
    private final StatsJob stats;
    private final ActivityStore activity;
    private final YieldPacks packs;
    private final me.dontshare.yieldanalytics.collect.HealthView health;

    public ApiRoutes(LiveMonitor live, StatsJob stats, ActivityStore activity, YieldPacks packs,
                     me.dontshare.yieldanalytics.collect.HealthView health) {
        this.health = health;
        this.live = live;
        this.stats = stats;
        this.activity = activity;
        this.packs = packs;
    }

    private me.dontshare.yieldanalytics.admin.EditService edits;
    private me.dontshare.yieldanalytics.admin.PlayerDirectory directory;
    private Access access;

    /** The editor's parts - set once, before the site starts. */
    public void setAdmin(me.dontshare.yieldanalytics.admin.EditService edits,
                         me.dontshare.yieldanalytics.admin.PlayerDirectory directory, Access access) {
        this.edits = edits;
        this.directory = directory;
        this.access = access;
    }

    Object get(String path, Map<String, String> query, Access.Caller caller) {
        return switch (path) {
            case "whoami" -> whoami(caller);
            case "directory" -> directory.page(query.get("q"), query.get("sort"), !"asc".equals(query.get("dir")),
                    intParam(query, "page", 1, 1, 1_000_000), intParam(query, "size", 50, 10, 200),
                    "1".equals(query.get("online")), "1".equals(query.get("bots")));
            case "schema" -> edits.schema();
            case "values" -> edits.values(uuidParam(query));
            case "pets" -> edits.pets(uuidParam(query));
            case "inventory" -> edits.inventory(uuidParam(query));
            case "edits" -> caller.role() != Access.Role.EDITOR ? null : edits.history(query.containsKey("uuid") ? uuidParam(query) : null, intParam(query, "limit", 50, 1, 500));
            default -> route(path, query);
        };
    }

    Object post(String path, Map<String, Object> body, Access.Caller caller) {
        return switch (path) {
            case "edit" -> edits.edit(caller, uuid(body.get("uuid")), text(body, "stat"), text(body, "value"));
            case "undo" -> edits.undo(caller, text(body, "id"));
            case "inventory/remove" -> edits.removeItem(caller, uuid(body.get("uuid")), (int) number(body.get("slot")));
            case "kick" -> edits.kick(caller, uuid(body.get("uuid")), body.get("reason") == null ? "" : String.valueOf(body.get("reason")));
            case "code/rotate" -> {
                access.rotate();
                yield whoami(caller);
            }
            default -> null;
        };
    }

    private Map<String, Object> whoami(Access.Caller caller) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", caller.role().name().toLowerCase(java.util.Locale.ROOT));
        out.put("name", caller.name());
        out.put("ip", caller.ip());
        if (caller.role() == Access.Role.EDITOR) {
            // Whitelisted: theirs to hand out.
            out.put("viewerCode", access.code());
        }
        out.put("codeExpiresAt", access.codeExpiresAt());
        return out;
    }

    private static UUID uuidParam(Map<String, String> query) {
        return uuid(query.get("uuid"));
    }

    private static UUID uuid(Object raw) {
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a player id.");
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key + ".");
        }
        return value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                ? String.valueOf(n.longValue()) : String.valueOf(value);
    }

    private Object route(String path, Map<String, String> query) {
        return switch (path) {
            case "overview" -> overview();
            case "live" -> liveView();
            case "stats" -> stats.snapshot();
            case "tutorial" -> tutorial();
            case "activity" -> activity(intParam(query, "hours", 48, 1, 24 * 90));
            case "retention" -> stats.snapshot().getOrDefault("retention", Map.of());
            case "performance" -> performance(intParam(query, "hours", 6, 1, 24 * 30));
            case "diagnostics" -> health.snapshot();
            case "players" -> search(query.getOrDefault("q", ""));
            case "player" -> query.containsKey("uuid") ? playerById(uuidParam(query)) : player(query.getOrDefault("name", ""));
            default -> null;
        };
    }

    private static int intParam(Map<String, String> query, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(query.getOrDefault(key, String.valueOf(fallback)))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Map<String, Object> overview() {
        Map<String, Object> snapshot = live.snapshot();
        Map<String, Object> statsSnapshot = stats.snapshot();
        Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Map<String, Long> today = ActivityStore.sum(activity.hoursSince(startOfDay));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", snapshot.getOrDefault("online", 0));
        out.put("bots", snapshot.getOrDefault("bots", 0));
        out.put("peakToday", snapshot.getOrDefault("peakToday", 0));
        out.put("allTimePeak", snapshot.getOrDefault("allTimePeak", 0));
        out.put("uptimeSeconds", snapshot.getOrDefault("uptimeSeconds", 0));
        out.put("health", snapshot.getOrDefault("health", Map.of()));
        out.put("today", today);
        out.put("players", statsSnapshot.getOrDefault("players", Map.of()));
        out.put("statsGeneratedAt", statsSnapshot.getOrDefault("generatedAt", null));
        Object tutorialBlock = statsSnapshot.get("tutorial");
        out.put("tutorialCompletionRate", tutorialBlock instanceof Map<?, ?> map ? map.get("completionRate") : null);
        Object retention = statsSnapshot.get("retention");
        if (retention instanceof Map<?, ?> map) {
            out.put("dau", map.get("dau"));
            out.put("wau", map.get("wau"));
            out.put("mau", map.get("mau"));
        }
        long sessions = today.getOrDefault("sessionsEnded", 0L);
        out.put("avgSessionMinutesToday", sessions == 0 ? null : today.getOrDefault("sessionMs", 0L) / 60_000.0 / sessions);
        out.put("onlineSeries", series(activity.samplesSince(Instant.now().minus(24, ChronoUnit.HOURS), 288), "online"));
        return out;
    }

    private Map<String, Object> liveView() {
        Map<String, Object> snapshot = live.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("t", snapshot.getOrDefault("t", 0));
        out.put("online", snapshot.getOrDefault("online", 0));
        out.put("bots", snapshot.getOrDefault("bots", 0));
        out.put("zones", snapshot.getOrDefault("zones", List.of()));
        return out;
    }

    private Map<String, Object> tutorial() {
        Map<String, Object> out = new LinkedHashMap<>();
        Object block = stats.snapshot().get("tutorial");
        if (block instanceof Map<?, ?> map) {
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
        }
        Map<String, Long> week = ActivityStore.sum(activity.hoursSince(Instant.now().minus(7, ChronoUnit.DAYS)));
        Map<String, Object> recent = new LinkedHashMap<>();
        recent.put("started", week.getOrDefault("tutorialStarted", 0L));
        recent.put("completed", week.getOrDefault("tutorialCompleted", 0L));
        recent.put("skipped", week.getOrDefault("tutorialSkipped", 0L));
        long completed = week.getOrDefault("tutorialCompleted", 0L);
        recent.put("avgMinutesToComplete", completed == 0 ? null : week.getOrDefault("tutorialCompletedMs", 0L) / 60_000.0 / completed);
        out.put("last7Days", recent);
        return out;
    }

    private Map<String, Object> activity(int hours) {
        List<Document> docs = activity.hoursSince(Instant.now().minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("hour", doc.getString("_id"));
            Document counters = doc.get("c", Document.class);
            row.put("c", counters != null ? counters : Map.of());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", hours);
        out.put("rows", rows);
        out.put("totals", ActivityStore.sum(docs));
        return out;
    }

    private Map<String, Object> performance(int hours) {
        Map<String, Object> snapshot = live.snapshot();
        List<Document> samples = activity.samplesSince(Instant.now().minus(hours, ChronoUnit.HOURS), 360);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("health", snapshot.getOrDefault("health", Map.of()));
        out.put("systems", snapshot.getOrDefault("systems", List.of()));
        out.put("packetsByType", snapshot.getOrDefault("packetsByType", List.of()));
        out.put("packetsBySystem", snapshot.getOrDefault("packetsBySystem", List.of()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Document sample : samples) {
            Map<String, Object> row = new LinkedHashMap<>();
            Date at = sample.getDate("t");
            row.put("t", at != null ? at.getTime() : 0);
            for (String key : List.of("online", "bots", "tps", "mspt", "msptP95", "msptMax", "heapMb", "heapMaxMb",
                    "pluginPps", "trackedMs", "dbQueue")) {
                row.put(key, sample.get(key));
            }
            row.put("zones", sample.get("zones"));
            rows.add(row);
        }
        out.put("samples", rows);
        return out;
    }

    private static List<Map<String, Object>> series(List<Document> samples, String key) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Document sample : samples) {
            Date at = sample.getDate("t");
            if (at == null) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", at.getTime());
            point.put("v", sample.get(key));
            points.add(point);
        }
        return points;
    }

    private List<Map<String, Object>> search(String prefix) {
        if (prefix.isBlank() || !SAFE_NAME.matcher(prefix).matches()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Document doc : activity.database().getCollection("playerData")
                .find(Filters.regex("core.username", "^" + prefix, "i"))
                .projection(Projections.include("core.username", "core.lastSeen"))
                .limit(10)) {
            Document core = doc.get("core", Document.class);
            if (core == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", core.getString("username"));
            row.put("lastSeen", core.get("lastSeen"));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> playerById(UUID id) {
        String name = directory.nameOf(id);
        return name == null ? null : player(name);
    }

    private Map<String, Object> player(String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a valid player name.");
        }
        Document doc = activity.database().getCollection("playerData")
                .find(Filters.regex("core.username", "^" + name + "$", "i"))
                .projection(Projections.include("core", "packs.coins", "packs.diamonds", "packs.rebirths",
                        "packs.prestiges", "packs.rollCount", "packs.lifetimeCubeKills", "packs.lifetimeCoinsEarned",
                        "packs.unlockedZoneIds", "packs.pets.instanceId", "packs.pets.itemId", "packs.pets.level",
                        "packs.pets.shiny", "packs.equippedPetIds", "packs.bestLuckOneIn", "packs.bestLuckItemId",
                        "packs.teamId", "packs.credits", "leveling.level", "quests.loginStreak", "ranks.donorRankId",
                        "analytics", "upgrades.levels"))
                .first();
        if (doc == null) {
            return null;
        }
        Document core = sub(doc, "core");
        Document pack = sub(doc, "packs");
        Document analytics = sub(doc, "analytics");
        Map<String, Object> out = new LinkedHashMap<>();
        String username = core.getString("username");
        out.put("name", username);
        out.put("uuid", String.valueOf(doc.get("_id")));

        Map<String, Object> onlineRow = null;
        Object players = live.snapshot().get("players");
        if (players instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> row && String.valueOf(row.get("name")).equalsIgnoreCase(username)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) row;
                    onlineRow = cast;
                }
            }
        }
        out.put("online", onlineRow != null);
        out.put("live", onlineRow);
        out.put("firstSeen", firstNonZero(number(analytics.get("firstSeen")), number(core.get("firstJoined"))));
        out.put("lastSeen", Math.max(number(analytics.get("lastSeen")), number(core.get("lastSeen"))));
        out.put("playtimeMs", number(analytics.get("playtimeMs")));
        out.put("sessions", number(analytics.get("sessions")));
        out.put("longestSessionMs", number(analytics.get("longestSessionMs")));
        out.put("coins", Formatting.format(StatsJob.bigValue(pack.get("coins"))));
        out.put("diamonds", Formatting.format(StatsJob.bigValue(pack.get("diamonds"))));
        out.put("credits", Formatting.format(StatsJob.bigValue(pack.get("credits"))));
        out.put("coinsEarned", Formatting.format(StatsJob.bigValue(pack.get("lifetimeCoinsEarned"))));
        out.put("rebirths", number(pack.get("rebirths")));
        out.put("prestiges", number(pack.get("prestiges")));
        out.put("eggsHatched", number(pack.get("rollCount")));
        out.put("cubesBroken", number(pack.get("lifetimeCubeKills")));
        out.put("zonesUnlocked", pack.getList("unlockedZoneIds", Object.class, List.of()));
        out.put("level", number(sub(doc, "leveling").get("level")));
        out.put("loginStreak", number(sub(doc, "quests").get("loginStreak")));
        out.put("donorRank", sub(doc, "ranks").getString("donorRankId"));
        out.put("inTeam", pack.get("teamId") != null);
        out.put("upgradeLevels", sub(doc, "upgrades").get("levels"));
        Map<String, Object> tutorialState = new LinkedHashMap<>();
        tutorialState.put("step", number(core.get("tutorialStep")));
        tutorialState.put("skipped", Boolean.TRUE.equals(core.getBoolean("tutorialSkipped")));
        tutorialState.put("outcome", analytics.getString("tutorialOutcome"));
        tutorialState.put("startedAt", number(analytics.get("tutorialStartedAt")));
        tutorialState.put("finishedAt", number(analytics.get("tutorialFinishedAt")));
        out.put("tutorial", tutorialState);
        out.put("zoneReachedAt", analytics.get("zoneReachedAt"));
        Map<String, String> zoneNames = new LinkedHashMap<>();
        if (live.snapshot().get("zones") instanceof List<?> zoneList) {
            for (Object entry : zoneList) {
                if (entry instanceof Map<?, ?> zone) {
                    zoneNames.put(String.valueOf(zone.get("id")), String.valueOf(zone.get("name")));
                }
            }
        }
        out.put("zoneNames", zoneNames);

        List<?> pets = pack.getList("pets", Object.class, List.of());
        List<?> equipped = pack.getList("equippedPetIds", Object.class, List.of());
        List<Map<String, Object>> equippedRows = new ArrayList<>();
        Map<String, Integer> byRarity = new LinkedHashMap<>();
        for (Object entry : pets) {
            if (!(entry instanceof Document pet)) {
                continue;
            }
            Optional<ItemDefinition> item = packs.getItemRegistry().find(String.valueOf(pet.getString("itemId")));
            item.ifPresent(definition -> byRarity.merge(definition.rarityId(), 1, Integer::sum));
            if (equipped.contains(pet.get("instanceId"))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", item.map(definition -> LiveMonitor.plain(definition.displayName())).orElse(pet.getString("itemId")));
                row.put("rarity", item.map(ItemDefinition::rarityId).orElse(""));
                row.put("level", number(pet.get("level")));
                row.put("shiny", Boolean.TRUE.equals(pet.getBoolean("shiny")));
                equippedRows.add(row);
            }
        }
        out.put("petsOwned", pets.size());
        out.put("petsByRarity", byRarity);
        out.put("equipped", equippedRows);
        String bestItem = pack.getString("bestLuckItemId");
        if (bestItem != null) {
            Map<String, Object> luck = new LinkedHashMap<>();
            luck.put("item", packs.getItemRegistry().find(bestItem).map(definition -> LiveMonitor.plain(definition.displayName())).orElse(bestItem));
            luck.put("oneIn", number(pack.get("bestLuckOneIn")));
            out.put("bestLuck", luck);
        }
        return out;
    }

    private static Document sub(Document doc, String key) {
        Object value = doc.get(key);
        return value instanceof Document document ? document : new Document();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static long firstNonZero(long a, long b) {
        return a > 0 ? a : b;
    }
}
