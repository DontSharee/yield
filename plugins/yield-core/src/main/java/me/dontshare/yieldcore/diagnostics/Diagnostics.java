package me.dontshare.yieldcore.diagnostics;

import me.dontshare.yieldcore.perf.PerfTracker;
import me.dontshare.yieldcore.status.ServerHealth;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The server checking itself: lag spikes and what caused them
 * ({@link TickMonitor}), memory that only goes up ({@link MemoryMonitor}),
 * plugin state that leaks ({@link LeakScanner}) and player data that failed
 * to save ({@link DataHealth}) - summed up as a list of {@link Finding}s for
 * {@code /yield status}, the analytics site and its alerts.
 * <p>
 * Everything here watches; nothing changes how the game plays.
 */
public final class Diagnostics {

    /** How much a finding matters. */
    public enum Severity { INFO, WARN, CRITICAL }

    /** One thing worth knowing. {@code key} stays the same while the problem lasts, for de-duplicating alerts. */
    public record Finding(Severity severity, String key, String title, String detail) {
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    /** Spikes this soon after start-up are worlds and caches warming up, not problems. */
    private static final long WARMUP_MS = 3 * 60_000L;

    private static TickMonitor ticks;
    private static MemoryMonitor memory;
    private static LeakScanner leaks;

    private Diagnostics() {
    }

    /** From core's onEnable, on the main thread. */
    public static void start(JavaPlugin plugin) {
        if (ticks != null) {
            return;
        }
        ticks = new TickMonitor();
        memory = new MemoryMonitor();
        leaks = new LeakScanner();
        Bukkit.getPluginManager().registerEvents(ticks, plugin);
        Bukkit.getPluginManager().registerEvents(leaks, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, memory::minute, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, PerfTracker.timed("core.leakscan", leaks::tick), 1L, 1L);
        LeakScanner.watchStatics(
                me.dontshare.yieldcore.packet.PacketEntityManager.class,
                me.dontshare.yieldcore.packet.EntityClickRegistry.class,
                me.dontshare.yieldcore.fakeblock.FakeBlockClickRegistry.class);
    }

    public static void stop() {
        if (ticks != null) {
            ticks.stop();
            HandlerList.unregisterAll(ticks);
        }
        if (memory != null) {
            memory.stop();
        }
        if (leaks != null) {
            HandlerList.unregisterAll(leaks);
        }
    }

    public static TickMonitor ticks() {
        return ticks;
    }

    public static MemoryMonitor memory() {
        return memory;
    }

    public static LeakScanner leaks() {
        return leaks;
    }

    public static boolean running() {
        return ticks != null;
    }

    /** Main thread. Everything currently wrong, worst first; empty when all is well. */
    public static List<Finding> findings() {
        if (ticks == null) {
            return List.of();
        }
        List<Finding> found = new ArrayList<>();
        long now = System.currentTimeMillis();
        lag(found, now);

        ServerHealth health = ServerHealth.read();
        if (health.tps1m() < 18) {
            found.add(new Finding(Severity.WARN, "lag.tps", String.format(Locale.ROOT, "TPS is %.1f", health.tps1m()),
                    String.format(Locale.ROOT, "Ticks average %.1f ms (%.1f ms at p95) - /yield status systems shows where the time goes",
                            health.msptAvg(), health.msptP95())));
        }
        if (health.dbQueued() > 200) {
            found.add(new Finding(Severity.WARN, "data.backlog", "Database is falling behind",
                    health.dbQueued() + " database jobs are waiting - saves are being delayed. Check the database's own load and latency."));
        }
        memory(found, now);
        for (LeakScanner.Suspect suspect : leaks.suspects()) {
            found.add(new Finding(Severity.WARN, suspect.key(), suspect.problem() + ": " + suspect.path(),
                    suspect.detail() + " [" + suspect.plugin() + "]"));
        }
        List<DataHealth.Failure> failures = DataHealth.since(now - 30 * 60_000L);
        if (!failures.isEmpty()) {
            DataHealth.Failure last = failures.getFirst();
            found.add(new Finding(Severity.CRITICAL, "data.saves",
                    failures.size() + " player save" + (failures.size() == 1 ? "" : "s") + " failed in the last 30 minutes",
                    "Latest: " + last.store() + " data for " + last.player() + " at " + CLOCK.format(Instant.ofEpochMilli(last.at()))
                            + " - " + last.reason() + ". Those players' newest progress isn't in the database yet."));
        }
        found.sort(Comparator.comparing(Finding::severity).reversed());
        return found;
    }

    private static void lag(List<Finding> found, long now) {
        List<TickMonitor.Spike> recent = ticks.spikesSince(now - 10 * 60_000L).stream()
                .filter(spike -> spike.at() >= ticks.startedAt() + WARMUP_MS)
                .toList();
        if (recent.isEmpty()) {
            return;
        }
        TickMonitor.Spike worst = recent.stream().max(Comparator.comparingDouble(TickMonitor.Spike::ms)).orElseThrow();
        List<TickMonitor.Spike> big = recent.stream().filter(spike -> spike.ms() >= 250).toList();
        if (worst.ms() >= 1000) {
            found.add(new Finding(Severity.CRITICAL, "lag.freeze",
                    String.format(Locale.ROOT, "Server froze for %.1f s", worst.ms() / 1000),
                    "At " + CLOCK.format(Instant.ofEpochMilli(worst.at())) + " - " + worst.cause()
                            + ". /yield status spikes has the full breakdown."));
        } else if (big.size() >= 3) {
            found.add(new Finding(Severity.WARN, "lag.spikes",
                    big.size() + " lag spikes over 250 ms in the last 10 minutes",
                    String.format(Locale.ROOT, "Worst %.0f ms. Most often: %s", worst.ms(), commonest(big))));
        } else if (recent.size() >= 20) {
            found.add(new Finding(Severity.INFO, "lag.minor",
                    recent.size() + " ticks over " + (int) TickMonitor.SPIKE_MS + " ms in the last 10 minutes",
                    "Players may notice stutter. Most often: " + commonest(recent)));
        }
    }

    /** The cause behind most of these spikes, ignoring the per-spike timings in it. */
    private static String commonest(List<TickMonitor.Spike> spikes) {
        Map<String, Integer> counts = new HashMap<>();
        for (TickMonitor.Spike spike : spikes) {
            counts.merge(spike.cause().replaceAll(" \\(\\d+ ms\\)", ""), 1, Integer::sum);
        }
        Map.Entry<String, Integer> top = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        return top.getKey() + " (" + top.getValue() + " of " + spikes.size() + ")";
    }

    private static void memory(List<Finding> found, long now) {
        MemoryMonitor.Trend trend = memory.trend();
        long max = memory.maxMb();
        long live = trend.enoughData() ? trend.liveMb() : memory.lastLiveMb();
        boolean steady = trend.onlineNow() <= Math.max(trend.onlineStart() + 5, trend.onlineStart() * 13 / 10);
        if (trend.enoughData() && trend.risingBuckets() >= 4 && trend.mbPerHour() > 0 && steady && live > max / 2) {
            found.add(new Finding(trend.hoursToFull() < 3 ? Severity.CRITICAL : Severity.WARN, "memory.leak",
                    String.format(Locale.ROOT, "Memory keeps growing: +%s/hour", MemoryMonitor.mb(Math.round(trend.mbPerHour()))),
                    String.format(Locale.ROOT, "Memory still in use after garbage collection has risen for %d minutes straight, to %s of %s,"
                                    + " with the player count steady. At this rate it runs out in about %.1f hours."
                                    + " /yield status leaks shows what's holding it.",
                            trend.risingBuckets() * 15, MemoryMonitor.mb(live), MemoryMonitor.mb(max), trend.hoursToFull())));
        }
        if (live >= 0 && live > max * 85 / 100) {
            found.add(new Finding(Severity.CRITICAL, "memory.full", "Memory nearly full",
                    MemoryMonitor.mb(live) + " of " + MemoryMonitor.mb(max) + " is still in use right after garbage collection."
                            + " Expect lag from constant collection, then a crash - raise -Xmx or find what's holding it."));
        }
        long pausedMs = memory.pauseMsOverLast(5);
        if (pausedMs > 15_000) {
            found.add(new Finding(Severity.WARN, "memory.gc",
                    String.format(Locale.ROOT, "Garbage collection took %.1f s of the last 5 minutes", pausedMs / 1000.0),
                    "The server is stopped while it collects - usually a sign memory is short or something allocates heavily."));
        }
        List<MemoryMonitor.Pause> pauses = memory.longPauses().stream()
                .filter(pause -> pause.at() >= now - 10 * 60_000L && pause.ms() >= 1000).toList();
        if (!pauses.isEmpty()) {
            MemoryMonitor.Pause pause = pauses.getFirst();
            found.add(new Finding(Severity.WARN, "memory.pause",
                    String.format(Locale.ROOT, "A %.1f s garbage-collection pause", pause.ms() / 1000.0),
                    pause.collector() + " (" + pause.cause() + ") at " + CLOCK.format(Instant.ofEpochMilli(pause.at()))
                            + ", " + MemoryMonitor.mb(pause.beforeMb()) + " → " + MemoryMonitor.mb(pause.afterMb())));
        }
    }

    public static String clock(long millis) {
        return CLOCK.format(Instant.ofEpochMilli(millis));
    }
}
