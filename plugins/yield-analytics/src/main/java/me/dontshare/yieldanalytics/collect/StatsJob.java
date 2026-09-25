package me.dontshare.yieldanalytics.collect;

import com.mongodb.client.model.Projections;
import me.dontshare.yieldanalytics.data.ActivityStore;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldzones.YieldZones;
import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bson.Document;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Everything worked out across every player who has ever joined: averages
 * and distributions of their progress, the tutorial funnel, how far through
 * the zones people get and how long it takes, what pets they own, who leads,
 * and how many come back.
 * <p>
 * One pass over the player collection per run, projected down to the fields
 * used, on a database thread; the result replaces the previous snapshot in
 * one step, so a reader never sees half a run.
 */
public final class StatsJob {

    private static final int[] RETENTION_DAYS = {1, 3, 7, 14, 30};
    private static final int TOP = 10;

    private final DatabaseManager database;
    private final ActivityStore activity;
    private final YieldPacks packs;
    private final YieldZones zones;
    private final TutorialSteps tutorial;
    private final Logger logger;
    private final int leftAfterDays;
    private volatile Map<String, Object> snapshot = Map.of("ready", false);
    private volatile boolean running;

    public StatsJob(DatabaseManager database, ActivityStore activity, YieldPacks packs, YieldZones zones,
                    TutorialSteps tutorial, Logger logger, int leftAfterDays) {
        this.database = database;
        this.activity = activity;
        this.packs = packs;
        this.zones = zones;
        this.tutorial = tutorial;
        this.logger = logger;
        this.leftAfterDays = leftAfterDays;
    }

    public Map<String, Object> snapshot() {
        return snapshot;
    }

    /** Kicks off a run on a database thread unless one is already going. Any thread. */
    public void refreshAsync() {
        if (running) {
            return;
        }
        running = true;
        // Game data the run needs, read on the main thread before it leaves.
        Set<UUID> online = new HashSet<>();
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());
        }
        List<ZoneDefinition> zoneList = List.copyOf(zones.getZones().values());
        Map<String, ItemDefinition> items = new HashMap<>();
        for (ItemDefinition item : packs.getItemRegistry().all()) {
            items.put(item.id(), item);
        }
        Map<String, Rarity> rarities = new HashMap<>();
        for (Rarity rarity : packs.getRarityRegistry().all()) {
            rarities.put(rarity.id(), rarity);
        }
        tutorial.reload();
        List<String> steps = tutorial.labels();
        database.supplyAsync(() -> {
            try {
                snapshot = compute(online, zoneList, items, rarities, steps);
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Analytics stats run failed", e);
            } finally {
                running = false;
            }
            return null;
        });
    }

    private Map<String, Object> compute(Set<UUID> online, List<ZoneDefinition> zoneList, Map<String, ItemDefinition> items,
                                        Map<String, Rarity> rarities, List<String> steps) {
        long started = System.currentTimeMillis();
        long now = started;
        long day = 86_400_000L;

        Distribution coins = new Distribution("coins", "Coins", "coins", true);
        Distribution diamonds = new Distribution("diamonds", "Diamonds", "diamonds", true);
        Distribution lifetimeCoins = new Distribution("lifetimeCoins", "Coins earned (lifetime)", "coins", true);
        Distribution rebirths = new Distribution("rebirths", "Rebirths", "", false);
        Distribution prestiges = new Distribution("prestiges", "Prestiges", "", false);
        Distribution eggs = new Distribution("eggsHatched", "Eggs hatched", "", true);
        Distribution kills = new Distribution("cubeKills", "Cubes broken", "", true);
        Distribution petsOwned = new Distribution("petsOwned", "Pets owned", "", false);
        Distribution petsEquipped = new Distribution("petsEquipped", "Pets equipped", "", false);
        Distribution zonesUnlocked = new Distribution("zonesUnlocked", "Zones unlocked", "", false);
        Distribution level = new Distribution("level", "Player level", "", false);
        Distribution streak = new Distribution("loginStreak", "Login streak", "days", false);
        Distribution playtime = new Distribution("playtime", "Playtime", Distribution.DURATION, false);
        Distribution sessionCount = new Distribution("sessions", "Sessions", "", false);
        Distribution sessionLength = new Distribution("avgSession", "Average session", Distribution.DURATION, false);
        Distribution upgrades = new Distribution("upgradeLevels", "Upgrade levels bought", "", false);
        Distribution blocktree = new Distribution("blocktreeTiers", "Block tree tiers claimed", "", false);
        Distribution achievements = new Distribution("achievements", "Achievements claimed", "", false);
        Distribution skills = new Distribution("skillLevels", "Skill levels", "", false);
        Distribution bossDamage = new Distribution("bossDamage", "Boss damage (lifetime)", "", true);
        Distribution daysSinceJoin = new Distribution("sinceFirstJoin", "Time since first join", Distribution.DURATION, false);

        int total = 0;
        int activeToday = 0;
        int active7 = 0;
        int active30 = 0;
        int new7 = 0;
        int inTeam = 0;
        Map<String, Integer> donorRanks = new LinkedHashMap<>();

        // Tutorial.
        int stepCount = steps.size();
        int[] reachedStep = new int[stepCount + 1];
        int[] stoppedAtStep = new int[stepCount + 1];
        int tutorialCompleted = 0;
        int tutorialSkipped = 0;
        int tutorialInProgress = 0;
        int tutorialLeft = 0;
        List<Double> tutorialMinutes = new ArrayList<>();

        // Zones.
        Map<String, Integer> reachedZone = new LinkedHashMap<>();
        Map<String, Integer> unlockedZone = new LinkedHashMap<>();
        Map<String, Integer> furthestZone = new LinkedHashMap<>();
        Map<String, List<Double>> hoursToZone = new HashMap<>();
        Map<String, Integer> zoneIndex = new HashMap<>();
        for (int i = 0; i < zoneList.size(); i++) {
            ZoneDefinition zone = zoneList.get(i);
            zoneIndex.put(zone.id(), i);
            reachedZone.put(zone.id(), 0);
            unlockedZone.put(zone.id(), 0);
            furthestZone.put(zone.id(), 0);
        }

        // Pets.
        Map<String, int[]> byRarity = new LinkedHashMap<>(); // owned, shiny, huge, players
        Map<String, Integer> petCounts = new HashMap<>();
        List<Map<String, Object>> luck = new ArrayList<>();

        // Leaders.
        List<Leader> topCoins = new ArrayList<>();
        List<Leader> topRebirths = new ArrayList<>();
        List<Leader> topPlaytime = new ArrayList<>();
        List<Leader> topEggs = new ArrayList<>();
        List<Leader> topKills = new ArrayList<>();

        for (Document doc : database.getCollection("playerData").find().projection(Projections.include(
                "core.username", "core.firstJoined", "core.lastSeen", "core.tutorialStep", "core.tutorialSkipped",
                "packs.coins", "packs.diamonds", "packs.rebirths", "packs.prestiges", "packs.rollCount",
                "packs.lifetimeCubeKills", "packs.lifetimeCoinsEarned", "packs.lifetimeBossDamage",
                "packs.unlockedZoneIds", "packs.pets.itemId", "packs.pets.shiny", "packs.equippedPetIds",
                "packs.bestLuckOneIn", "packs.bestLuckItemId", "packs.teamId",
                "leveling.level", "quests.loginStreak", "upgrades.levels", "blocktree.claimedTiers",
                "achievements.claimedAchievementIds", "skilltree.levels", "ranks.donorRankId", "analytics"))) {
            Object rawId = doc.get("_id");
            if (!(rawId instanceof UUID id)) {
                continue;
            }
            Document core = sub(doc, "core");
            String name = core.getString("username");
            if (Exclusions.excluded(id) || Exclusions.excludedName(name)) {
                continue;
            }
            if (name == null) {
                name = id.toString().substring(0, 8);
            }
            Document pack = sub(doc, "packs");
            Document analytics = sub(doc, "analytics");
            total++;

            long firstSeen = firstNonZero(longOf(analytics, "firstSeen"), longOf(core, "firstJoined"));
            long lastSeen = Math.max(longOf(analytics, "lastSeen"), longOf(core, "lastSeen"));
            boolean isOnline = online.contains(id);
            long sinceLast = isOnline ? 0 : now - lastSeen;
            if (isOnline || sinceLast < day) {
                activeToday++;
            }
            if (isOnline || sinceLast < 7 * day) {
                active7++;
            }
            if (isOnline || sinceLast < 30 * day) {
                active30++;
            }
            if (firstSeen > 0 && now - firstSeen < 7 * day) {
                new7++;
            }
            if (firstSeen > 0) {
                daysSinceJoin.add((now - firstSeen) / 60_000.0);
            }

            BigInteger coinValue = big(pack.get("coins"));
            coins.add(coinValue.doubleValue());
            diamonds.add(big(pack.get("diamonds")).doubleValue());
            BigInteger earned = big(pack.get("lifetimeCoinsEarned"));
            lifetimeCoins.add(earned.doubleValue());
            int rebirthCount = intOf(pack, "rebirths");
            rebirths.add(rebirthCount);
            prestiges.add(intOf(pack, "prestiges"));
            long rolls = longOf(pack, "rollCount");
            eggs.add(rolls);
            long killCount = longOf(pack, "lifetimeCubeKills");
            kills.add(killCount);
            bossDamage.add(longOf(pack, "lifetimeBossDamage"));
            if (pack.get("teamId") != null) {
                inTeam++;
            }

            List<?> pets = pack.getList("pets", Object.class, List.of());
            petsOwned.add(pets.size());
            petsEquipped.add(pack.getList("equippedPetIds", Object.class, List.of()).size());
            Set<String> rarityOwners = new HashSet<>();
            for (Object entry : pets) {
                if (!(entry instanceof Document pet)) {
                    continue;
                }
                String itemId = pet.getString("itemId");
                ItemDefinition item = itemId != null ? items.get(itemId) : null;
                if (item == null) {
                    continue;
                }
                petCounts.merge(itemId, 1, Integer::sum);
                int[] tally = byRarity.computeIfAbsent(item.rarityId(), key -> new int[4]);
                tally[0]++;
                if (Boolean.TRUE.equals(pet.getBoolean("shiny"))) {
                    tally[1]++;
                }
                if (item.huge()) {
                    tally[2]++;
                }
                if (rarityOwners.add(item.rarityId())) {
                    tally[3]++;
                }
            }
            long bestOneIn = longOf(pack, "bestLuckOneIn");
            String bestItem = pack.getString("bestLuckItemId");
            if (bestOneIn > 0 && bestItem != null) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("player", name);
                row.put("item", items.containsKey(bestItem) ? LiveMonitor.plain(items.get(bestItem).displayName()) : bestItem);
                row.put("oneIn", bestOneIn);
                luck.add(row);
            }

            List<?> unlocked = pack.getList("unlockedZoneIds", Object.class, List.of());
            zonesUnlocked.add(unlocked.size());
            int furthest = -1;
            for (Object zoneId : unlocked) {
                String zone = String.valueOf(zoneId);
                if (unlockedZone.containsKey(zone)) {
                    unlockedZone.merge(zone, 1, Integer::sum);
                    furthest = Math.max(furthest, zoneIndex.get(zone));
                }
            }
            Document reached = sub(analytics, "zoneReachedAt");
            for (Map.Entry<String, Object> entry : reached.entrySet()) {
                if (reachedZone.containsKey(entry.getKey()) && entry.getValue() instanceof Number at) {
                    reachedZone.merge(entry.getKey(), 1, Integer::sum);
                    furthest = Math.max(furthest, zoneIndex.get(entry.getKey()));
                    if (firstSeen > 0 && at.longValue() >= firstSeen) {
                        hoursToZone.computeIfAbsent(entry.getKey(), key -> new ArrayList<>())
                                .add((at.longValue() - firstSeen) / 3_600_000.0);
                    }
                }
            }
            if (furthest >= 0) {
                furthestZone.merge(zoneList.get(furthest).id(), 1, Integer::sum);
            }

            level.add(intOf(sub(doc, "leveling"), "level", 1));
            streak.add(intOf(sub(doc, "quests"), "loginStreak"));
            long playMs = longOf(analytics, "playtimeMs");
            playtime.add(playMs / 60_000.0);
            int sessionTotal = intOf(analytics, "sessions");
            sessionCount.add(sessionTotal);
            if (sessionTotal > 0) {
                sessionLength.add(playMs / 60_000.0 / sessionTotal);
            }
            upgrades.add(sumValues(sub(sub(doc, "upgrades"), "levels")));
            blocktree.add(sub(doc, "blocktree").getList("claimedTiers", Object.class, List.of()).size());
            achievements.add(sub(doc, "achievements").getList("claimedAchievementIds", Object.class, List.of()).size());
            skills.add(sumValues(sub(sub(doc, "skilltree"), "levels")));
            String donor = sub(doc, "ranks").getString("donorRankId");
            donorRanks.merge(donor == null || donor.isBlank() ? "none" : donor, 1, Integer::sum);

            // Tutorial.
            if (stepCount > 0) {
                int step = Math.min(intOf(core, "tutorialStep"), stepCount);
                boolean skipped = Boolean.TRUE.equals(core.getBoolean("tutorialSkipped"));
                for (int i = 0; i <= step; i++) {
                    reachedStep[i]++;
                }
                if (skipped) {
                    tutorialSkipped++;
                } else if (step >= stepCount) {
                    tutorialCompleted++;
                    long startedAt = longOf(analytics, "tutorialStartedAt");
                    long finishedAt = longOf(analytics, "tutorialFinishedAt");
                    if (startedAt > 0 && finishedAt >= startedAt) {
                        tutorialMinutes.add((finishedAt - startedAt) / 60_000.0);
                    }
                } else if (!isOnline && sinceLast > leftAfterDays * day) {
                    tutorialLeft++;
                    stoppedAtStep[step]++;
                } else {
                    tutorialInProgress++;
                }
            }

            offer(topCoins, name, coinValue.doubleValue(), Formatting.format(coinValue));
            offer(topRebirths, name, rebirthCount, String.valueOf(rebirthCount));
            offer(topPlaytime, name, playMs, formatDuration(playMs));
            offer(topEggs, name, rolls, Formatting.format(rolls));
            offer(topKills, name, killCount, Formatting.format(killCount));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ready", true);
        out.put("generatedAt", now);
        out.put("computeMs", System.currentTimeMillis() - started);

        Map<String, Object> playersBlock = new LinkedHashMap<>();
        playersBlock.put("total", total);
        playersBlock.put("activeToday", activeToday);
        playersBlock.put("active7d", active7);
        playersBlock.put("active30d", active30);
        playersBlock.put("new7d", new7);
        playersBlock.put("inTeam", inTeam);
        out.put("players", playersBlock);

        out.put("numbers", List.of(coins.summary(), diamonds.summary(), lifetimeCoins.summary(), rebirths.summary(),
                prestiges.summary(), eggs.summary(), kills.summary(), petsOwned.summary(), petsEquipped.summary(),
                zonesUnlocked.summary(), level.summary(), streak.summary(), playtime.summary(), sessionCount.summary(),
                sessionLength.summary(), upgrades.summary(), blocktree.summary(), achievements.summary(),
                skills.summary(), bossDamage.summary(), daysSinceJoin.summary()));

        List<Map<String, Object>> zoneRows = new ArrayList<>();
        for (ZoneDefinition zone : zoneList) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", zone.id());
            row.put("name", LiveMonitor.plain(zone.displayName()));
            row.put("reached", Math.max(reachedZone.get(zone.id()), unlockedZone.get(zone.id())));
            row.put("unlocked", unlockedZone.get(zone.id()));
            row.put("furthest", furthestZone.get(zone.id()));
            List<Double> hours = hoursToZone.getOrDefault(zone.id(), List.of());
            List<Double> sorted = new ArrayList<>(hours);
            sorted.sort(Double::compare);
            row.put("medianHoursToReach", sorted.isEmpty() ? null : Distribution.percentile(sorted, 0.5));
            zoneRows.add(row);
        }
        out.put("zones", zoneRows);

        List<Map<String, Object>> rarityRows = new ArrayList<>();
        List<Rarity> orderedRarities = new ArrayList<>(rarities.values());
        orderedRarities.sort(Comparator.comparingInt(Rarity::sortOrder));
        for (Rarity rarity : orderedRarities) {
            int[] tally = byRarity.getOrDefault(rarity.id(), new int[4]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rarity.id());
            row.put("name", LiveMonitor.plain(rarity.displayName()));
            row.put("color", rarity.colorHex());
            row.put("owned", tally[0]);
            row.put("shiny", tally[1]);
            row.put("huge", tally[2]);
            row.put("players", tally[3]);
            rarityRows.add(row);
        }
        out.put("rarities", rarityRows);

        List<Map<String, Object>> petRows = new ArrayList<>();
        petCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(15).forEach(entry -> {
            ItemDefinition item = items.get(entry.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.getKey());
            row.put("name", item != null ? LiveMonitor.plain(item.displayName()) : entry.getKey());
            row.put("rarity", item != null ? item.rarityId() : "");
            row.put("count", entry.getValue());
            petRows.add(row);
        });
        out.put("popularPets", petRows);
        luck.sort(Comparator.comparingLong((Map<String, Object> row) -> (Long) row.get("oneIn")).reversed());
        out.put("luckiest", luck.subList(0, Math.min(TOP, luck.size())));

        Map<String, Object> leaders = new LinkedHashMap<>();
        leaders.put("coins", leaderRows(topCoins));
        leaders.put("rebirths", leaderRows(topRebirths));
        leaders.put("playtime", leaderRows(topPlaytime));
        leaders.put("eggs", leaderRows(topEggs));
        leaders.put("cubeKills", leaderRows(topKills));
        out.put("leaders", leaders);
        out.put("donorRanks", donorRanks);

        Map<String, Object> tutorialBlock = new LinkedHashMap<>();
        tutorialBlock.put("stepCount", stepCount);
        int startedCount = stepCount > 0 ? reachedStep[0] : 0;
        tutorialBlock.put("started", startedCount);
        tutorialBlock.put("completed", tutorialCompleted);
        tutorialBlock.put("skipped", tutorialSkipped);
        tutorialBlock.put("inProgress", tutorialInProgress);
        tutorialBlock.put("leftDuring", tutorialLeft);
        tutorialBlock.put("leftAfterDays", leftAfterDays);
        tutorialBlock.put("completionRate", startedCount == 0 ? 0 : tutorialCompleted / (double) startedCount);
        List<Double> sortedMinutes = new ArrayList<>(tutorialMinutes);
        sortedMinutes.sort(Double::compare);
        tutorialBlock.put("medianMinutesToComplete", sortedMinutes.isEmpty() ? null : Distribution.percentile(sortedMinutes, 0.5));
        List<Map<String, Object>> stepRows = new ArrayList<>();
        for (int i = 0; i <= stepCount && stepCount > 0; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("label", i < stepCount ? steps.get(i) : "Finished");
            row.put("reached", reachedStep[i]);
            row.put("leftHere", stoppedAtStep[i]);
            row.put("share", startedCount == 0 ? 0 : reachedStep[i] / (double) startedCount);
            stepRows.add(row);
        }
        tutorialBlock.put("steps", stepRows);
        out.put("tutorial", tutorialBlock);

        out.put("retention", retention());
        return out;
    }

    /** Daily actives, newcomers, and what share of each day's newcomers came back 1, 3, 7, 14 and 30 days later. */
    private Map<String, Object> retention() {
        List<Document> days = activity.days(60);
        Map<String, Set<Object>> playersByDay = new LinkedHashMap<>();
        Map<String, Set<Object>> newByDay = new LinkedHashMap<>();
        List<Map<String, Object>> dayRows = new ArrayList<>();
        for (Document doc : days) {
            String dayKey = doc.getString("_id");
            Set<Object> players = new HashSet<>(doc.getList("players", Object.class, List.of()));
            Set<Object> fresh = new HashSet<>(doc.getList("newPlayers", Object.class, List.of()));
            players.removeIf(id -> id instanceof UUID uuid && Exclusions.excluded(uuid));
            playersByDay.put(dayKey, players);
            newByDay.put(dayKey, fresh);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", dayKey);
            row.put("active", players.size());
            row.put("new", fresh.size());
            row.put("returning", players.size() - fresh.size());
            row.put("peakOnline", doc.getInteger("peakOnline", 0));
            dayRows.add(row);
        }
        LocalDate today = ActivityStore.today();
        List<Map<String, Object>> cohorts = new ArrayList<>();
        for (Map.Entry<String, Set<Object>> entry : newByDay.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            LocalDate cohortDay = LocalDate.parse(entry.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", entry.getKey());
            row.put("size", entry.getValue().size());
            for (int offset : RETENTION_DAYS) {
                LocalDate later = cohortDay.plusDays(offset);
                if (later.isAfter(today)) {
                    row.put("d" + offset, null);
                    continue;
                }
                Set<Object> active = playersByDay.getOrDefault(later.toString(), Set.of());
                long back = entry.getValue().stream().filter(active::contains).count();
                row.put("d" + offset, back / (double) entry.getValue().size());
            }
            cohorts.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", dayRows);
        out.put("cohorts", cohorts);
        out.put("dau", activeOver(playersByDay, today, 1));
        out.put("wau", activeOver(playersByDay, today, 7));
        out.put("mau", activeOver(playersByDay, today, 30));
        return out;
    }

    private static int activeOver(Map<String, Set<Object>> playersByDay, LocalDate today, int days) {
        Set<Object> union = new HashSet<>();
        for (int i = 0; i < days; i++) {
            union.addAll(playersByDay.getOrDefault(today.minusDays(i).toString(), Set.of()));
        }
        return union.size();
    }

    private record Leader(String name, double value, String shown) {
    }

    private static void offer(List<Leader> board, String name, double value, String shown) {
        if (value <= 0) {
            return;
        }
        board.add(new Leader(name, value, shown));
        if (board.size() > TOP * 4) {
            board.sort(Comparator.comparingDouble(Leader::value).reversed());
            board.subList(TOP, board.size()).clear();
        }
    }

    private static List<Map<String, Object>> leaderRows(List<Leader> board) {
        board.sort(Comparator.comparingDouble(Leader::value).reversed());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Leader leader : board.subList(0, Math.min(TOP, board.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", leader.name());
            row.put("value", leader.shown());
            rows.add(row);
        }
        return rows;
    }

    static String formatDuration(long millis) {
        long minutes = millis / 60_000L;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + "h " + (minutes % 60) + "m" : (hours / 24) + "d " + (hours % 24) + "h";
    }

    static Document sub(Document doc, String key) {
        Object value = doc == null ? null : doc.get(key);
        return value instanceof Document document ? document : new Document();
    }

    static long longOf(Document doc, String key) {
        Object value = doc.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    static int intOf(Document doc, String key) {
        return intOf(doc, key, 0);
    }

    static int intOf(Document doc, String key, int fallback) {
        Object value = doc.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long firstNonZero(long a, long b) {
        return a > 0 ? a : b;
    }

    private static double sumValues(Document doc) {
        double total = 0;
        for (Object value : doc.values()) {
            if (value instanceof Number number) {
                total += number.doubleValue();
            }
        }
        return total;
    }

    /** A stored big number read leniently: a string, a plain number, or zero. */
    public static BigInteger bigValue(Object value) {
        return big(value);
    }

    static BigInteger big(Object value) {
        try {
            if (value instanceof String text && !text.isBlank()) {
                return new BigInteger(text.trim());
            }
            if (value instanceof Number number) {
                return BigDecimal.valueOf(number.doubleValue()).toBigInteger();
            }
        } catch (NumberFormatException ignored) {
            // Falls through to zero - one malformed balance shouldn't sink the run.
        }
        return BigInteger.ZERO;
    }
}
