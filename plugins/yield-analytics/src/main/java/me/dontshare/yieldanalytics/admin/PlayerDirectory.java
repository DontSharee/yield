package me.dontshare.yieldanalytics.admin;

import me.dontshare.yieldanalytics.collect.StatsJob;
import me.dontshare.yieldanalytics.data.ActivityStore;
import me.dontshare.yieldcore.text.Formatting;
import org.bson.Document;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Every player who has ever joined, as one sortable, searchable list - read
 * from the database in one pass and kept for a minute, so paging and sorting
 * a big list costs nothing but the first look. An edit re-reads just that
 * player.
 */
public final class PlayerDirectory {

    private static final long FRESH_MS = 60_000L;

    /** One player in the list. */
    public record Row(UUID id, String name, long firstSeen, long lastSeen, long playtimeMs,
                      BigInteger coins, BigInteger diamonds, int rebirths, int prestiges, int level,
                      int zones, int pets, String tutorial) {
    }

    private final ActivityStore activity;
    private final Supplier<Set<UUID>> online;
    private volatile Map<UUID, Row> rows = Map.of();
    private volatile long loadedAt;

    public PlayerDirectory(ActivityStore activity, Supplier<Set<UUID>> online) {
        this.activity = activity;
        this.online = online;
    }

    /** Web thread. */
    public Map<String, Object> page(String search, String sort, boolean descending, int page, int size,
                                    boolean onlineOnly, boolean includeBots) {
        Map<UUID, Row> all = fresh();
        Set<UUID> onlineNow = online.get();
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Row> matching = new ArrayList<>();
        for (Row row : all.values()) {
            if (!includeBots && isBot(row.name())) {
                continue;
            }
            if (onlineOnly && !onlineNow.contains(row.id())) {
                continue;
            }
            if (!needle.isEmpty() && !row.name().toLowerCase(Locale.ROOT).contains(needle)
                    && !row.id().toString().startsWith(needle)) {
                continue;
            }
            matching.add(row);
        }
        Comparator<Row> order = switch (sort == null ? "" : sort) {
            case "name" -> Comparator.comparing(Row::name, String.CASE_INSENSITIVE_ORDER);
            case "firstSeen" -> Comparator.comparingLong(Row::firstSeen);
            case "playtime" -> Comparator.comparingLong(Row::playtimeMs);
            case "coins" -> Comparator.comparing(Row::coins);
            case "diamonds" -> Comparator.comparing(Row::diamonds);
            case "rebirths" -> Comparator.comparingInt(Row::rebirths);
            case "prestiges" -> Comparator.comparingInt(Row::prestiges);
            case "level" -> Comparator.comparingInt(Row::level);
            case "zones" -> Comparator.comparingInt(Row::zones);
            case "pets" -> Comparator.comparingInt(Row::pets);
            default -> Comparator.comparingLong(Row::lastSeen);
        };
        matching.sort(descending ? order.reversed() : order);
        int pages = Math.max(1, (matching.size() + size - 1) / size);
        int current = Math.min(Math.max(1, page), pages);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row row : matching.subList((current - 1) * size, Math.min(matching.size(), current * size))) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("uuid", row.id().toString());
            map.put("name", row.name());
            map.put("online", onlineNow.contains(row.id()));
            map.put("bot", isBot(row.name()));
            map.put("firstSeen", row.firstSeen());
            map.put("lastSeen", row.lastSeen());
            map.put("playtimeMs", row.playtimeMs());
            map.put("coins", Formatting.format(row.coins()));
            map.put("diamonds", Formatting.format(row.diamonds()));
            map.put("rebirths", row.rebirths());
            map.put("prestiges", row.prestiges());
            map.put("level", row.level());
            map.put("zones", row.zones());
            map.put("pets", row.pets());
            map.put("tutorial", row.tutorial());
            out.add(map);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", all.size());
        result.put("matching", matching.size());
        result.put("page", current);
        result.put("pages", pages);
        result.put("loadedAt", loadedAt);
        result.put("rows", out);
        return result;
    }

    /** The name on record for a player, or null if they've never joined. */
    public String nameOf(UUID id) {
        Row row = fresh().get(id);
        return row != null ? row.name() : null;
    }

    /** A player by exact name, ignoring case. */
    public UUID idOf(String name) {
        for (Row row : fresh().values()) {
            if (row.name().equalsIgnoreCase(name)) {
                return row.id();
            }
        }
        return null;
    }

    /** After an edit: re-read this one player now. */
    public void refresh(UUID id) {
        List<Document> docs = activity.database().getCollection("playerData")
                .aggregate(List.of(new Document("$match", new Document("_id", id)), projection())).into(new ArrayList<>());
        synchronized (this) {
            Map<UUID, Row> copy = new HashMap<>(rows);
            if (docs.isEmpty()) {
                copy.remove(id);
            } else {
                Row row = toRow(docs.getFirst());
                if (row != null) {
                    copy.put(id, row);
                }
            }
            rows = copy;
        }
    }

    private Map<UUID, Row> fresh() {
        if (System.currentTimeMillis() - loadedAt < FRESH_MS) {
            return rows;
        }
        synchronized (this) {
            if (System.currentTimeMillis() - loadedAt < FRESH_MS) {
                return rows;
            }
            Map<UUID, Row> loaded = new HashMap<>();
            for (Document doc : activity.database().getCollection("playerData").aggregate(List.of(projection()))) {
                Row row = toRow(doc);
                if (row != null) {
                    loaded.put(row.id(), row);
                }
            }
            rows = loaded;
            loadedAt = System.currentTimeMillis();
            return loaded;
        }
    }

    /** Only what the list shows - sizes of the pet and zone lists, never the lists themselves. */
    private static Document projection() {
        return new Document("$project", new Document()
                .append("name", "$core.username")
                .append("firstJoined", "$core.firstJoined")
                .append("coreLastSeen", "$core.lastSeen")
                .append("tutorialStep", "$core.tutorialStep")
                .append("tutorialSkipped", "$core.tutorialSkipped")
                .append("firstSeen", "$analytics.firstSeen")
                .append("lastSeen", "$analytics.lastSeen")
                .append("playtimeMs", "$analytics.playtimeMs")
                .append("tutorialOutcome", "$analytics.tutorialOutcome")
                .append("coins", "$packs.coins")
                .append("diamonds", "$packs.diamonds")
                .append("rebirths", "$packs.rebirths")
                .append("prestiges", "$packs.prestiges")
                .append("level", "$leveling.level")
                .append("zones", new Document("$size", new Document("$ifNull", List.of("$packs.unlockedZoneIds", List.of()))))
                .append("pets", new Document("$size", new Document("$ifNull", List.of("$packs.pets", List.of())))));
    }

    private static Row toRow(Document doc) {
        if (!(doc.get("_id") instanceof UUID id)) {
            return null;
        }
        String name = doc.getString("name");
        long firstSeen = firstNonZero(number(doc.get("firstSeen")), number(doc.get("firstJoined")));
        long lastSeen = Math.max(number(doc.get("lastSeen")), number(doc.get("coreLastSeen")));
        String tutorial = Boolean.TRUE.equals(doc.get("tutorialSkipped")) ? "skipped"
                : "completed".equals(doc.getString("tutorialOutcome")) ? "done"
                : "step " + (number(doc.get("tutorialStep")) + 1);
        return new Row(id, name != null ? name : id.toString().substring(0, 8), firstSeen, lastSeen,
                number(doc.get("playtimeMs")), StatsJob.bigValue(doc.get("coins")), StatsJob.bigValue(doc.get("diamonds")),
                (int) number(doc.get("rebirths")), (int) number(doc.get("prestiges")), (int) Math.max(1, number(doc.get("level"))),
                (int) number(doc.get("zones")), (int) number(doc.get("pets")), tutorial);
    }

    /** yield-loadtest's bots - named, since offline ones aren't marked anywhere else. */
    private static boolean isBot(String name) {
        return name != null && name.startsWith("LT_Bot");
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static long firstNonZero(long a, long b) {
        return a > 0 ? a : b;
    }
}
