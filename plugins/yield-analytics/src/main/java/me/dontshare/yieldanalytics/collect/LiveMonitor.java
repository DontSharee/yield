package me.dontshare.yieldanalytics.collect;

import me.dontshare.yieldanalytics.data.ActivityStore;
import me.dontshare.yieldanalytics.webhook.WebhookService;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.perf.PerfTracker;
import me.dontshare.yieldcore.player.PlayerProfile;
import me.dontshare.yieldcore.status.ServerHealth;
import me.dontshare.yieldcore.status.SyntheticPlayers;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzones.YieldZones;
import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the server looks like right now - who is on, where, doing what, and
 * how the server is holding up - rebuilt every couple of seconds on the main
 * thread (the only place game state may be read) and handed to the web
 * threads as a finished, immutable snapshot. Once a minute it also records a
 * sample for the history charts.
 */
public final class LiveMonitor {

    private final PlayerDataStore<PlayerProfile> coreStore;
    private final YieldPacks packs;
    private final YieldZones zones;
    private final SessionTracker sessions;
    private final TutorialSteps tutorial;
    private final ActivityStore activity;
    private final DatabaseManager database;
    private final WebhookService webhooks;
    private final long startedAt = System.currentTimeMillis();
    private volatile Map<String, Object> snapshot = Map.of();
    private volatile int peakToday;
    private volatile int allTimePeak;
    private volatile String peakDay = ActivityStore.dayKey(java.time.Instant.now());

    public LiveMonitor(PlayerDataStore<PlayerProfile> coreStore, YieldPacks packs, YieldZones zones,
                       SessionTracker sessions, TutorialSteps tutorial, ActivityStore activity,
                       DatabaseManager database, WebhookService webhooks) {
        this.coreStore = coreStore;
        this.packs = packs;
        this.zones = zones;
        this.sessions = sessions;
        this.tutorial = tutorial;
        this.activity = activity;
        this.database = database;
        this.webhooks = webhooks;
    }

    public Map<String, Object> snapshot() {
        return snapshot;
    }

    public long startedAt() {
        return startedAt;
    }

    public int peakToday() {
        return peakToday;
    }

    public int allTimePeak() {
        return allTimePeak;
    }

    /** Off the main thread, once at start: the peaks recorded before this boot. */
    public void loadPeaks() {
        List<Document> today = activity.days(0);
        peakToday = today.isEmpty() ? 0 : today.getLast().getInteger("peakOnline", 0);
        allTimePeak = activity.allTimePeak();
    }

    /** Main thread, every couple of seconds. */
    public void refresh() {
        long now = System.currentTimeMillis();
        Map<String, Map<String, Object>> byZone = new LinkedHashMap<>();
        for (ZoneDefinition zone : zones.getZones().values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", zone.id());
            entry.put("name", plain(zone.displayName()));
            entry.put("players", new ArrayList<Map<String, Object>>());
            byZone.put(zone.id(), entry);
        }
        Map<String, Object> outside = new LinkedHashMap<>();
        outside.put("id", "");
        outside.put("name", "Outside zones");
        outside.put("players", new ArrayList<Map<String, Object>>());

        List<Map<String, Object>> players = new ArrayList<>();
        int real = 0;
        int bots = 0;
        int tutorialSteps = tutorial.count();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            boolean bot = SyntheticPlayers.is(id);
            boolean counted = !Exclusions.excluded(id);
            if (bot) {
                bots++;
            }
            if (counted) {
                real++;
            }
            ZoneDefinition zone = zones.getCubeService().currentZoneOf(player);
            PackPlayerProfile pack = packs.getPlayerStore().getCached(id);
            PlayerProfile core = coreStore.getCached(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", player.getName());
            row.put("uuid", id.toString());
            row.put("bot", bot);
            row.put("counted", counted);
            row.put("zone", zone != null ? zone.id() : "");
            row.put("zoneName", zone != null ? plain(zone.displayName()) : "Outside zones");
            long started = sessions.sessionStartOf(id);
            row.put("sessionSeconds", started > 0 ? (now - started) / 1000 : 0);
            if (pack != null) {
                row.put("coins", Formatting.format(pack.getCoins()));
                row.put("diamonds", Formatting.format(pack.getDiamonds()));
                row.put("rebirths", pack.getRebirths());
                row.put("pets", pack.getPets().size());
                row.put("equipped", pack.getEquippedPetIds().size());
                row.put("zonesUnlocked", pack.getUnlockedZoneIds().size());
            }
            if (core != null && tutorialSteps > 0) {
                row.put("tutorial", core.isTutorialSkipped() ? "skipped"
                        : core.getTutorialStep() >= tutorialSteps ? "done"
                        : (core.getTutorialStep() + 1) + "/" + tutorialSteps);
            }
            players.add(row);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) (zone != null && byZone.containsKey(zone.id())
                    ? byZone.get(zone.id()) : outside).get("players");
            list.add(row);
        }
        players.sort(Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> zoneList = new ArrayList<>(byZone.values());
        zoneList.add(outside);
        for (Map<String, Object> zone : zoneList) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) zone.get("players");
            list.sort(Comparator.comparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER));
            zone.put("count", list.size());
        }

        String today = ActivityStore.dayKey(java.time.Instant.now());
        if (!today.equals(peakDay)) {
            peakDay = today;
            peakToday = 0;
        }
        if (real > peakToday) {
            peakToday = real;
        }
        if (real > allTimePeak) {
            int previous = allTimePeak;
            allTimePeak = real;
            if (previous > 0) {
                webhooks.newPeak(real, previous);
            }
        }

        ServerHealth health = ServerHealth.read();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("t", now);
        result.put("online", real);
        result.put("bots", bots);
        result.put("peakToday", peakToday);
        result.put("allTimePeak", allTimePeak);
        result.put("uptimeSeconds", (now - startedAt) / 1000);
        result.put("players", players);
        result.put("zones", zoneList);
        result.put("health", healthMap(health));
        result.put("systems", PerfTracker.sections().stream().limit(40).map(section -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("system", section.system());
            row.put("msPerTick", round(section.msPerTick(), 3));
            row.put("avgMsPerRun", round(section.avgMsPerRun(), 3));
            row.put("maxMsPerRun", round(section.maxMsPerRun(), 2));
            row.put("runs", section.runs());
            return row;
        }).toList());
        result.put("packetsByType", packetRows(PerfTracker.packets()));
        result.put("packetsBySystem", packetRows(PerfTracker.packetsBySystem()));
        snapshot = result;
        webhooks.checkHealth(health, real);
    }

    /** Main thread, once a minute: the history sample, playtime, and today's active players. */
    public void minute() {
        sessions.minuteTick();
        Map<String, Object> live = snapshot;
        ServerHealth health = ServerHealth.read();
        Document zoneCounts = new Document();
        Object zoneList = live.get("zones");
        if (zoneList instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> zone && !String.valueOf(zone.get("id")).isEmpty()) {
                    int count = 0;
                    if (zone.get("players") instanceof List<?> zonePlayers) {
                        for (Object row : zonePlayers) {
                            if (row instanceof Map<?, ?> player && Boolean.TRUE.equals(player.get("counted"))) {
                                count++;
                            }
                        }
                    }
                    zoneCounts.append(String.valueOf(zone.get("id")), count);
                }
            }
        }
        Document sample = new Document("t", new Date())
                .append("online", live.getOrDefault("online", 0))
                .append("bots", live.getOrDefault("bots", 0))
                .append("tps", round(health.tps1m(), 2))
                .append("mspt", round(health.msptAvg(), 2))
                .append("msptP95", round(health.msptP95(), 2))
                .append("msptMax", round(health.msptMax(), 2))
                .append("heapMb", health.heapUsedMb())
                .append("heapMaxMb", health.heapMaxMb())
                .append("pluginPps", Math.round(health.packetsPerSecond()))
                .append("trackedMs", round(health.trackedMsPerTick(), 2))
                .append("dbQueue", health.dbQueued())
                .append("zones", zoneCounts);
        database.supplyAsync(() -> {
            activity.writeSample(sample);
            activity.flush();
            return null;
        });
    }

    private static List<Map<String, Object>> packetRows(List<PerfTracker.PacketStats> stats) {
        return stats.stream().limit(25).map(stat -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", stat.type());
            row.put("perSecond", round(stat.perSecond(), 1));
            row.put("total", stat.total());
            return row;
        }).toList();
    }

    static Map<String, Object> healthMap(ServerHealth health) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tps1m", round(health.tps1m(), 2));
        map.put("tps5m", round(health.tps5m(), 2));
        map.put("tps15m", round(health.tps15m(), 2));
        map.put("msptAvg", round(health.msptAvg(), 2));
        map.put("msptP95", round(health.msptP95(), 2));
        map.put("msptMax", round(health.msptMax(), 2));
        map.put("heapUsedMb", health.heapUsedMb());
        map.put("heapMaxMb", health.heapMaxMb());
        map.put("dbQueued", health.dbQueued());
        map.put("dbActive", health.dbActive());
        map.put("cachedRecords", health.cachedRecords());
        map.put("writesInFlight", health.writesInFlight());
        map.put("pluginPacketsPerSecond", Math.round(health.packetsPerSecond()));
        map.put("trackedMsPerTick", round(health.trackedMsPerTick(), 2));
        return map;
    }

    static double round(double value, int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    /** A display name without its colour codes or tags. */
    public static String plain(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?i)[&§][0-9a-fk-orx#]", "").replaceAll("<[^>]*>", "").trim();
    }
}
