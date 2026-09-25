package me.dontshare.yieldanalytics.data;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import me.dontshare.yieldcore.database.DatabaseManager;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Everything the analytics record over time, in three collections:
 * <ul>
 *   <li>{@code analytics_hourly} - one document per UTC hour of counters
 *       ({@code c.cubeKills}, {@code c.hatch_mythic}...), incremented in
 *       batches once a minute rather than per event;</li>
 *   <li>{@code analytics_daily} - one per UTC day: who played, who was new,
 *       and the peak online count - what retention is worked out from;</li>
 *   <li>{@code analytics_samples} - one per minute of server health and
 *       online counts, for the charts.</li>
 * </ul>
 * Hourly and per-minute documents expire after the configured retention.
 */
public final class ActivityStore {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final DatabaseManager database;
    private final Logger logger;
    private final MongoCollection<Document> hourly;
    private final MongoCollection<Document> daily;
    private final MongoCollection<Document> samples;
    /** Hour bucket to counter name to the amount not yet written. */
    private final Map<String, Map<String, LongAdder>> pending = new ConcurrentHashMap<>();

    public ActivityStore(DatabaseManager database, Logger logger) {
        this.database = database;
        this.logger = logger;
        this.hourly = database.getCollection("analytics_hourly");
        this.daily = database.getCollection("analytics_daily");
        this.samples = database.getCollection("analytics_samples");
    }

    /** Blocking - call off the main thread. */
    public void ensureIndexes(int retentionDays) {
        try {
            dropStaleTtl(hourly, "t_1", retentionDays);
            dropStaleTtl(samples, "t_1", retentionDays);
            hourly.createIndex(Indexes.ascending("t"), new IndexOptions().expireAfter((long) retentionDays, TimeUnit.DAYS));
            samples.createIndex(Indexes.ascending("t"), new IndexOptions().expireAfter((long) retentionDays, TimeUnit.DAYS));
            daily.createIndex(Indexes.ascending("t"));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not create analytics indexes", e);
        }
    }

    /** A TTL index keeps its first expiry; a changed retention needs the old index gone first. */
    private void dropStaleTtl(MongoCollection<Document> collection, String name, int retentionDays) {
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) {
                Number expire = index.get("expireAfterSeconds", Number.class);
                if (expire == null || expire.longValue() != TimeUnit.DAYS.toSeconds(retentionDays)) {
                    collection.dropIndex(name);
                }
            }
        }
    }

    public static String hourKey(Instant at) {
        return HOUR.format(at);
    }

    public static String dayKey(Instant at) {
        return DAY.format(at);
    }

    /** The same counts since the last {@link #drainPeriod} - what a webhook summary reports. */
    private final Map<String, LongAdder> period = new ConcurrentHashMap<>();

    /** Everything counted since the last call, and a fresh start. */
    public Map<String, Long> drainPeriod() {
        Map<String, Long> out = new HashMap<>();
        for (String key : List.copyOf(period.keySet())) {
            LongAdder adder = period.remove(key);
            if (adder != null) {
                out.put(key, adder.sum());
            }
        }
        return out;
    }

    /** Adds to this hour's counter; written on the next {@link #flush}. Any thread. */
    public void count(String counter, long amount) {
        if (amount == 0) {
            return;
        }
        period.computeIfAbsent(counter, key -> new LongAdder()).add(amount);
        pending.computeIfAbsent(hourKey(Instant.now()), key -> new ConcurrentHashMap<>())
                .computeIfAbsent(counter, key -> new LongAdder())
                .add(amount);
    }

    public void count(String counter) {
        count(counter, 1);
    }

    /** Blocking - writes every pending counter with one update per hour bucket. */
    public void flush() {
        for (String hour : List.copyOf(pending.keySet())) {
            Map<String, LongAdder> counters = pending.remove(hour);
            if (counters == null || counters.isEmpty()) {
                continue;
            }
            List<Bson> increments = new ArrayList<>();
            for (Map.Entry<String, LongAdder> entry : counters.entrySet()) {
                long value = entry.getValue().sum();
                if (value != 0) {
                    increments.add(Updates.inc("c." + safeKey(entry.getKey()), value));
                }
            }
            if (increments.isEmpty()) {
                continue;
            }
            Date start = Date.from(java.time.LocalDateTime.parse(hour + ":00").toInstant(ZoneOffset.UTC));
            increments.add(Updates.setOnInsert("t", start));
            try {
                hourly.updateOne(Filters.eq("_id", hour), Updates.combine(increments), new UpdateOptions().upsert(true));
            } catch (RuntimeException e) {
                // Put them back for the next flush rather than losing a minute of counts.
                for (Map.Entry<String, LongAdder> entry : counters.entrySet()) {
                    pending.computeIfAbsent(hour, key -> new ConcurrentHashMap<>())
                            .computeIfAbsent(entry.getKey(), key -> new LongAdder()).add(entry.getValue().sum());
                }
                logger.log(Level.WARNING, "Could not write analytics counters", e);
                return;
            }
        }
    }

    /** Mongo field names can't hold dots or start with $. */
    private static String safeKey(String key) {
        return key.replace('.', '_').replace('$', '_');
    }

    /** Blocking. Marks these players as having played today (and, for newcomers, as new today). */
    public void markActive(Collection<UUID> players, Collection<UUID> newPlayers, int onlineNow) {
        Instant now = Instant.now();
        String day = dayKey(now);
        List<Bson> updates = new ArrayList<>();
        if (!players.isEmpty()) {
            updates.add(Updates.addEachToSet("players", List.copyOf(players)));
        }
        if (!newPlayers.isEmpty()) {
            updates.add(Updates.addEachToSet("newPlayers", List.copyOf(newPlayers)));
        }
        updates.add(Updates.max("peakOnline", onlineNow));
        updates.add(Updates.setOnInsert("t", Date.from(now.truncatedTo(ChronoUnit.DAYS))));
        try {
            daily.updateOne(Filters.eq("_id", day), Updates.combine(updates), new UpdateOptions().upsert(true));
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not record daily activity", e);
        }
    }

    /** Blocking. One minute's reading of the server. */
    public void writeSample(Document sample) {
        try {
            samples.insertOne(sample);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not write an analytics sample", e);
        }
    }

    /** Blocking. Hourly counter documents from {@code since}, oldest first. */
    public List<Document> hoursSince(Instant since) {
        List<Document> result = new ArrayList<>();
        hourly.find(Filters.gte("t", Date.from(since))).sort(Sorts.ascending("t")).into(result);
        return result;
    }

    /** Blocking. Samples from {@code since}, oldest first, thinned to at most {@code maxPoints}. */
    public List<Document> samplesSince(Instant since, int maxPoints) {
        List<Document> all = new ArrayList<>();
        samples.find(Filters.gte("t", Date.from(since))).sort(Sorts.ascending("t")).into(all);
        if (all.size() <= maxPoints) {
            return all;
        }
        int step = (int) Math.ceil(all.size() / (double) maxPoints);
        List<Document> thinned = new ArrayList<>();
        for (int i = 0; i < all.size(); i += step) {
            thinned.add(all.get(i));
        }
        return thinned;
    }

    /** Blocking. Daily documents for the last {@code days} days, oldest first. */
    public List<Document> days(int days) {
        String from = dayKey(Instant.now().minus(days, ChronoUnit.DAYS));
        List<Document> result = new ArrayList<>();
        daily.find(Filters.gte("_id", from)).sort(Sorts.ascending("_id")).into(result);
        return result;
    }

    /** Blocking. The highest online count ever recorded in a day. */
    public int allTimePeak() {
        Document top = daily.find().sort(Sorts.descending("peakOnline")).limit(1).first();
        return top == null ? 0 : top.getInteger("peakOnline", 0);
    }

    /** Totals of every counter across these hour documents. */
    public static Map<String, Long> sum(List<Document> hours) {
        Map<String, Long> totals = new HashMap<>();
        for (Document hour : hours) {
            Document counters = hour.get("c", Document.class);
            if (counters == null) {
                continue;
            }
            for (Map.Entry<String, Object> entry : counters.entrySet()) {
                if (entry.getValue() instanceof Number number) {
                    totals.merge(entry.getKey(), number.longValue(), Long::sum);
                }
            }
        }
        return totals;
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    public DatabaseManager database() {
        return database;
    }
}
