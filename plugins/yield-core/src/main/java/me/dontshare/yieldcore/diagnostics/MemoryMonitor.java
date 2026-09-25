package me.dontshare.yieldcore.diagnostics;

import com.sun.management.GarbageCollectionNotificationInfo;
import org.bukkit.Bukkit;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Watches memory the way a leak shows up.
 * <p>
 * "Heap used" on its own rises and falls with garbage all the time. What
 * matters is how much is still in use right after a collection, since that
 * part is actually being kept. This listens to every collection, keeps the
 * lowest after-collection reading each minute (the "live" heap), and looks
 * at how that floor moves over hours:
 * <ul>
 *   <li>a floor that keeps rising for an hour or more while player counts
 *       hold steady is the signature of a leak;</li>
 *   <li>a floor near the maximum means the server is short on memory,
 *       leak or not;</li>
 *   <li>long or frequent pauses are the collector struggling, which players
 *       feel as lag.</li>
 * </ul>
 */
public final class MemoryMonitor {

    private static final int MINUTES_KEPT = 6 * 60;
    /** The floor is compared across buckets this long. */
    private static final int BUCKET_MINUTES = 15;

    /** One minute: the live-heap floor (-1 if nothing collected), collections, pause time, online players. */
    public record Minute(long at, long liveMb, int collections, long pauseMs, long longestPauseMs, int online) {
    }

    /** One collector pause worth remembering. */
    public record Pause(long at, String collector, String cause, long ms, long beforeMb, long afterMb) {
    }

    /** What the floor is doing. {@code mbPerHour} is the trend over the last hour of buckets. */
    public record Trend(long liveMb, long maxMb, double mbPerHour, int risingBuckets, double hoursToFull,
                        int onlineStart, int onlineNow, boolean enoughData) {
    }

    private final Set<String> heapPools = new HashSet<>();
    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final NotificationListener listener = this::onCollection;
    private final Deque<Minute> minutes = new ArrayDeque<>();
    private final Deque<Pause> longPauses = new ArrayDeque<>();
    private final long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

    // The minute being filled - written by the collector's notification thread.
    private long minuteFloor = -1;
    private int minuteCollections;
    private long minutePause;
    private long minuteLongest;
    private long lastLiveMb = -1;

    MemoryMonitor() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                heapPools.add(pool.getName());
            }
        }
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(listener, null, null);
                emitters.add(emitter);
            }
        }
    }

    void stop() {
        for (NotificationEmitter emitter : emitters) {
            try {
                emitter.removeNotificationListener(listener);
            } catch (ListenerNotFoundException ignored) {
                // Already gone.
            }
        }
    }

    public long maxMb() {
        return maxMb;
    }

    /** The most recent after-collection reading, or -1 before the first collection. */
    public synchronized long lastLiveMb() {
        return lastLiveMb;
    }

    public synchronized List<Minute> minutes() {
        return new ArrayList<>(minutes);
    }

    /** Pauses of 200 ms or more, newest first. */
    public synchronized List<Pause> longPauses() {
        List<Pause> copy = new ArrayList<>(longPauses);
        java.util.Collections.reverse(copy);
        return copy;
    }

    private void onCollection(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            return;
        }
        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        long before = heapMb(info.getGcInfo().getMemoryUsageBeforeGc());
        long after = heapMb(info.getGcInfo().getMemoryUsageAfterGc());
        String name = info.getGcName();
        boolean pause = !name.contains("Concurrent") && !name.endsWith("Cycles");
        long duration = info.getGcInfo().getDuration();
        synchronized (this) {
            lastLiveMb = after;
            minuteFloor = minuteFloor < 0 ? after : Math.min(minuteFloor, after);
            if (pause) {
                minuteCollections++;
                minutePause += duration;
                minuteLongest = Math.max(minuteLongest, duration);
                if (duration >= 200) {
                    longPauses.addLast(new Pause(System.currentTimeMillis(), name, info.getGcCause(), duration, before, after));
                    while (longPauses.size() > 50) {
                        longPauses.removeFirst();
                    }
                }
            }
        }
    }

    private long heapMb(Map<String, MemoryUsage> pools) {
        long used = 0;
        for (Map.Entry<String, MemoryUsage> entry : pools.entrySet()) {
            if (heapPools.contains(entry.getKey())) {
                used += entry.getValue().getUsed();
            }
        }
        return used / (1024 * 1024);
    }

    /** Main thread, once a minute. */
    void minute() {
        int online = Bukkit.getOnlinePlayers().size();
        synchronized (this) {
            minutes.addLast(new Minute(System.currentTimeMillis(), minuteFloor, minuteCollections, minutePause, minuteLongest, online));
            while (minutes.size() > MINUTES_KEPT) {
                minutes.removeFirst();
            }
            minuteFloor = -1;
            minuteCollections = 0;
            minutePause = 0;
            minuteLongest = 0;
        }
    }

    /** Collector pause time over the last {@code count} minutes, in ms. */
    public long pauseMsOverLast(int count) {
        List<Minute> list = minutes();
        long total = 0;
        for (int i = Math.max(0, list.size() - count); i < list.size(); i++) {
            total += list.get(i).pauseMs();
        }
        return total;
    }

    /**
     * The live-heap floor per {@value #BUCKET_MINUTES}-minute bucket over the
     * last few hours, and how it's moving. Needs at least an hour of buckets
     * to call a trend.
     */
    public Trend trend() {
        List<Minute> list = minutes();
        List<long[]> buckets = new ArrayList<>(); // {floorMb, averageOnline}
        for (int end = list.size(); end - BUCKET_MINUTES >= 0; end -= BUCKET_MINUTES) {
            long floor = -1;
            long online = 0;
            for (int i = end - BUCKET_MINUTES; i < end; i++) {
                Minute minute = list.get(i);
                if (minute.liveMb() >= 0) {
                    floor = floor < 0 ? minute.liveMb() : Math.min(floor, minute.liveMb());
                }
                online += minute.online();
            }
            buckets.addFirst(new long[]{floor, online / BUCKET_MINUTES});
        }
        buckets.removeIf(bucket -> bucket[0] < 0);
        long live = lastLiveMb();
        int onlineNow = Bukkit.getOnlinePlayers().size();
        if (buckets.size() < 5) {
            return new Trend(live, maxMb, 0, 0, Double.POSITIVE_INFINITY, onlineNow, onlineNow, false);
        }
        int rising = 0;
        for (int i = buckets.size() - 1; i > 0 && buckets.get(i)[0] > buckets.get(i - 1)[0]; i--) {
            rising++;
        }
        List<long[]> lastHour = buckets.subList(buckets.size() - 5, buckets.size());
        double perHour = (lastHour.getLast()[0] - lastHour.getFirst()[0]) / (4.0 * BUCKET_MINUTES / 60.0);
        long floorNow = buckets.getLast()[0];
        double hoursToFull = perHour <= 0 ? Double.POSITIVE_INFINITY : (maxMb - floorNow) / perHour;
        return new Trend(floorNow, maxMb, perHour, rising, hoursToFull,
                (int) lastHour.getFirst()[1], (int) lastHour.getLast()[1], true);
    }

    static String mb(long mb) {
        return mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024.0) : mb + " MB";
    }
}
