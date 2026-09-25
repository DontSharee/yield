package me.dontshare.yieldcore.perf;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Where the server's time and bandwidth go, per system.
 * <p>
 * Every repeating task worth watching is wrapped in {@link #timed}, and every
 * packet this codebase sends is counted by type in {@link #countPacket}. Once
 * a second the running totals roll into a one-minute window, which is what
 * {@code /yield status}, the load test and the analytics site read - so a
 * number there is always "over the last minute", never since boot.
 * <p>
 * Cheap enough to leave on: two {@code nanoTime} calls per task run and one
 * counter increment per packet.
 */
public final class PerfTracker {

    /** Seconds of history each figure is averaged over. */
    public static final int WINDOW_SECONDS = 60;

    private static final Map<String, Section> SECTIONS = new ConcurrentHashMap<>();
    private static final Map<String, Counter> PACKETS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Counter> PACKETS_BY_CLASS = new ConcurrentHashMap<>();
    /** Packets by the timed system that sent them; anything sent outside one (a click, a join) is "events". */
    private static final Map<String, Counter> PACKETS_BY_SYSTEM = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final String UNTIMED = "events";
    /** Server ticks seen in each second of the window - the divisor for "ms per tick". */
    private static final int[] TICKS = new int[WINDOW_SECONDS];
    private static volatile int ticksThisSecond;
    private static int slot;
    private static boolean started;
    /** The server thread - only its work is attributed to a tick (see {@link #thisTick}). */
    private static volatile Thread mainThread;
    /** Set at the start of every tick by the tick monitor. */
    private static volatile long tickId;

    private PerfTracker() {
    }

    /** Call once, from core's onEnable. */
    public static void start(JavaPlugin plugin) {
        if (started) {
            return;
        }
        started = true;
        mainThread = Thread.currentThread();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> ticksThisSecond++, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, PerfTracker::roll, 20L, 20L);
    }

    /** {@code task}, timed under {@code system} every time it runs. */
    public static Runnable timed(String system, Runnable task) {
        Section section = SECTIONS.computeIfAbsent(system, Section::new);
        return () -> {
            String outer = CURRENT.get();
            CURRENT.set(system);
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                section.record(System.nanoTime() - start);
                CURRENT.set(outer);
            }
        };
    }

    /** Marks the start of tick {@code id}; per-tick figures from here on belong to it. */
    public static void beginTick(long id) {
        tickId = id;
    }

    /** One system's time within the current tick. */
    public record TickSection(String system, double ms) {
    }

    /** Main thread: what each timed system has cost so far this tick, costliest first. */
    public static List<TickSection> thisTick() {
        long id = tickId;
        List<TickSection> result = new ArrayList<>();
        for (Section section : SECTIONS.values()) {
            if (section.tickOf == id && section.tickNanos > 0) {
                result.add(new TickSection(section.name, section.tickNanos / 1e6));
            }
        }
        result.sort(Comparator.comparingDouble(TickSection::ms).reversed());
        return result;
    }

    /** Adds time measured by the caller - for work that isn't one scheduled task (an event handler, a flush). */
    public static void record(String system, long nanos) {
        SECTIONS.computeIfAbsent(system, Section::new).record(nanos);
    }

    /**
     * Attributes packets sent from here on (on this thread) to {@code system}
     * - for splitting one timed task's traffic into its parts. Returns what
     * to hand back to {@link #exit}.
     */
    public static String enter(String system) {
        String outer = CURRENT.get();
        CURRENT.set(system);
        return outer;
    }

    public static void exit(String outer) {
        CURRENT.set(outer);
    }

    public static void countPacket(Object packet) {
        PACKETS_BY_CLASS.computeIfAbsent(packet.getClass(), type -> PACKETS.computeIfAbsent(label(type), Counter::new)).add(1);
        String system = CURRENT.get();
        PACKETS_BY_SYSTEM.computeIfAbsent(system != null ? system : UNTIMED, Counter::new).add(1);
    }

    private static String label(Class<?> type) {
        String name = type.getSimpleName();
        return name.startsWith("WrapperPlayServer") ? name.substring("WrapperPlayServer".length()) : name;
    }

    private static synchronized void roll() {
        TICKS[slot] = ticksThisSecond;
        ticksThisSecond = 0;
        for (Section section : SECTIONS.values()) {
            section.roll(slot);
        }
        for (Counter counter : PACKETS.values()) {
            counter.roll(slot);
        }
        for (Counter counter : PACKETS_BY_SYSTEM.values()) {
            counter.roll(slot);
        }
        slot = (slot + 1) % WINDOW_SECONDS;
    }

    private static int windowTicks() {
        int total = 0;
        for (int ticks : TICKS) {
            total += ticks;
        }
        return Math.max(1, total);
    }

    private static int windowSeconds() {
        int seconds = 0;
        for (int ticks : TICKS) {
            if (ticks > 0) {
                seconds++;
            }
        }
        return Math.max(1, seconds);
    }

    /** One system's cost over the window. {@code msPerTick} is its share of every 50 ms tick. */
    public record SectionStats(String system, double msPerTick, double avgMsPerRun, double maxMsPerRun, long runs) {
    }

    public record PacketStats(String type, double perSecond, long total) {
    }

    /** Every timed system, costliest first. */
    public static List<SectionStats> sections() {
        int ticks = windowTicks();
        List<SectionStats> result = new ArrayList<>();
        for (Section section : SECTIONS.values()) {
            long nanos = section.windowNanos();
            long runs = section.windowRuns();
            result.add(new SectionStats(section.name, nanos / 1e6 / ticks,
                    runs == 0 ? 0 : nanos / 1e6 / runs, section.windowMaxNanos() / 1e6, runs));
        }
        result.sort(Comparator.comparingDouble(SectionStats::msPerTick).reversed());
        return result;
    }

    /** Packets this codebase sent, by type, busiest first. */
    public static List<PacketStats> packets() {
        return rates(PACKETS);
    }

    /** Packets by the system that sent them, busiest first. */
    public static List<PacketStats> packetsBySystem() {
        return rates(PACKETS_BY_SYSTEM);
    }

    private static List<PacketStats> rates(Map<String, Counter> counters) {
        int seconds = windowSeconds();
        List<PacketStats> result = new ArrayList<>();
        for (Counter counter : counters.values()) {
            result.add(new PacketStats(counter.name, counter.window() / (double) seconds, counter.total.sum()));
        }
        result.sort(Comparator.comparingDouble(PacketStats::perSecond).reversed());
        return result;
    }

    public static double packetsPerSecond() {
        double total = 0;
        for (PacketStats stats : packets()) {
            total += stats.perSecond();
        }
        return total;
    }

    /** Total ms per tick across every timed system. */
    public static double trackedMsPerTick() {
        double total = 0;
        for (SectionStats stats : sections()) {
            total += stats.msPerTick();
        }
        return total;
    }

    private static final class Section {
        final String name;
        final LongAdder nanos = new LongAdder();
        final LongAdder runs = new LongAdder();
        final AtomicLong max = new AtomicLong();
        final long[] nanosBySlot = new long[WINDOW_SECONDS];
        final long[] runsBySlot = new long[WINDOW_SECONDS];
        final long[] maxBySlot = new long[WINDOW_SECONDS];

        Section(String name) {
            this.name = name;
        }

        /** Main-thread time in tick {@link #tickOf} - only ever touched on the main thread. */
        long tickOf = -1;
        long tickNanos;

        void record(long elapsed) {
            nanos.add(elapsed);
            runs.increment();
            max.accumulateAndGet(elapsed, Math::max);
            if (Thread.currentThread() == mainThread) {
                long id = tickId;
                if (tickOf != id) {
                    tickOf = id;
                    tickNanos = 0;
                }
                tickNanos += elapsed;
            }
        }

        void roll(int slot) {
            nanosBySlot[slot] = nanos.sumThenReset();
            runsBySlot[slot] = runs.sumThenReset();
            maxBySlot[slot] = max.getAndSet(0);
        }

        synchronized long windowNanos() {
            long total = 0;
            for (long value : nanosBySlot) {
                total += value;
            }
            return total;
        }

        synchronized long windowRuns() {
            long total = 0;
            for (long value : runsBySlot) {
                total += value;
            }
            return total;
        }

        synchronized long windowMaxNanos() {
            long peak = 0;
            for (long value : maxBySlot) {
                peak = Math.max(peak, value);
            }
            return peak;
        }
    }

    private static final class Counter {
        final String name;
        final LongAdder current = new LongAdder();
        final LongAdder total = new LongAdder();
        final long[] bySlot = new long[WINDOW_SECONDS];

        Counter(String name) {
            this.name = name;
        }

        void add(long amount) {
            current.add(amount);
            total.add(amount);
        }

        void roll(int slot) {
            bySlot[slot] = current.sumThenReset();
        }

        long window() {
            long sum = 0;
            for (long value : bySlot) {
                sum += value;
            }
            return sum;
        }
    }
}
