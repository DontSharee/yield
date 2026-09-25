package me.dontshare.yieldcore.diagnostics;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import me.dontshare.yieldcore.perf.PerfTracker;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Catches lag spikes and works out what caused them.
 * <p>
 * A background thread watches the tick in progress. Once a tick has run
 * longer than {@link #SAMPLE_AFTER_MS}, it starts copying the server thread's
 * stack every few milliseconds. If the tick ends up slower than
 * {@link #SPIKE_MS}, those stacks, the timed systems that ran in it
 * (see {@link PerfTracker#thisTick}) and any garbage-collection pause that
 * overlapped it are kept as a {@link Spike}. The stacks name the code that
 * was running during the slow part, including code nothing times.
 * <p>
 * Costs nothing on a healthy tick: two volatile writes and a GC-time read.
 */
public final class TickMonitor implements Listener {

    /** A tick at least this slow is recorded as a spike. A healthy tick is under 50 ms. */
    public static final double SPIKE_MS = 100;
    private static final long SAMPLE_AFTER_MS = 45;
    private static final long SAMPLE_EVERY_NANOS = 4_000_000L;
    private static final int MAX_SAMPLES = 250;
    private static final int HISTORY = 100;
    private static final int STACK_LINES = 18;

    /** One slow tick and what it was spent on. */
    public record Spike(long at, int tick, double ms, int online, double gcMs, String cause,
                        List<Culprit> culprits, List<PerfTracker.TickSection> systems, List<String> stack, int samples) {
    }

    /** Code seen in a share of a spike's stack samples. {@code where} is the innermost Yield frame, {@code inside} the top frame. */
    public record Culprit(String where, String plugin, String inside, double share) {
    }

    private final Thread main;
    private final List<GarbageCollectorMXBean> pauseCollectors = new ArrayList<>();
    private final Deque<Spike> spikes = new ArrayDeque<>();
    private final List<StackTraceElement[]> samples = new ArrayList<>();
    private final long startedAt = System.currentTimeMillis();
    private volatile int tickInProgress = -1;
    private volatile long tickStartNanos;
    private int samplesFor = -1;
    private long gcAtStart;
    private volatile boolean running = true;
    private final Thread sampler;
    private long totalSpikes;

    TickMonitor() {
        this.main = Thread.currentThread();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            // Concurrent collectors run beside the server thread; only pauses stop it.
            String name = bean.getName();
            if (!name.contains("Concurrent") && !name.equals("ZGC Cycles") && !name.equals("Shenandoah Cycles")) {
                pauseCollectors.add(bean);
            }
        }
        sampler = new Thread(this::sample, "Yield-TickMonitor");
        sampler.setDaemon(true);
        sampler.setPriority(Thread.MAX_PRIORITY);
        sampler.start();
    }

    void stop() {
        running = false;
    }

    public long startedAt() {
        return startedAt;
    }

    public synchronized long totalSpikes() {
        return totalSpikes;
    }

    /** Newest first. */
    public synchronized List<Spike> spikes() {
        List<Spike> copy = new ArrayList<>(spikes);
        java.util.Collections.reverse(copy);
        return copy;
    }

    /** Spikes recorded since {@code sinceMillis}, newest first. */
    public List<Spike> spikesSince(long sinceMillis) {
        return spikes().stream().filter(spike -> spike.at() >= sinceMillis).toList();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTickStart(ServerTickStartEvent event) {
        int tick = event.getTickNumber();
        PerfTracker.beginTick(tick);
        gcAtStart = gcMillis();
        tickStartNanos = System.nanoTime();
        synchronized (samples) {
            if (!samples.isEmpty()) {
                samples.clear();
            }
            samplesFor = tick;
        }
        tickInProgress = tick;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        int tick = tickInProgress;
        tickInProgress = -1;
        double ms = event.getTickDuration();
        if (ms < SPIKE_MS || tick < 0) {
            return;
        }
        List<StackTraceElement[]> taken;
        synchronized (samples) {
            taken = new ArrayList<>(samples);
            samples.clear();
        }
        double gcMs = Math.max(0, gcMillis() - gcAtStart);
        List<PerfTracker.TickSection> systems = PerfTracker.thisTick();
        record(new SpikeBuilder(tick, ms, gcMs, systems, taken).build());
    }

    private synchronized void record(Spike spike) {
        spikes.addLast(spike);
        totalSpikes++;
        while (spikes.size() > HISTORY) {
            spikes.removeFirst();
        }
    }

    private void sample() {
        while (running) {
            LockSupport.parkNanos(SAMPLE_EVERY_NANOS);
            int tick = tickInProgress;
            if (tick < 0 || (System.nanoTime() - tickStartNanos) / 1_000_000L < SAMPLE_AFTER_MS) {
                continue;
            }
            StackTraceElement[] stack = main.getStackTrace();
            synchronized (samples) {
                if (samplesFor == tick && tickInProgress == tick && samples.size() < MAX_SAMPLES) {
                    samples.add(stack);
                }
            }
        }
    }

    private long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean : pauseCollectors) {
            total += Math.max(0, bean.getCollectionTime());
        }
        return total;
    }

    /** Turns one slow tick's raw readings into a {@link Spike}. */
    private static final class SpikeBuilder {
        private final int tick;
        private final double ms;
        private final double gcMs;
        private final List<PerfTracker.TickSection> systems;
        private final List<StackTraceElement[]> samples;

        SpikeBuilder(int tick, double ms, double gcMs, List<PerfTracker.TickSection> systems, List<StackTraceElement[]> samples) {
            this.tick = tick;
            this.ms = ms;
            this.gcMs = gcMs;
            this.systems = systems;
            this.samples = samples;
        }

        Spike build() {
            Map<String, int[]> counts = new HashMap<>();
            Map<String, String[]> details = new HashMap<>();
            Map<String, StackTraceElement[]> examples = new HashMap<>();
            for (StackTraceElement[] stack : samples) {
                if (stack.length == 0) {
                    continue;
                }
                StackTraceElement ours = null;
                for (StackTraceElement frame : stack) {
                    if (frame.getClassName().startsWith("me.dontshare.")
                            && !frame.getClassName().startsWith("me.dontshare.yieldcore.perf.")
                            && !frame.getClassName().startsWith("me.dontshare.yieldcore.diagnostics.")) {
                        ours = frame;
                        break;
                    }
                }
                StackTraceElement top = firstInteresting(stack);
                String key = ours != null ? frame(ours) : "server: " + frame(top);
                counts.computeIfAbsent(key, k -> new int[1])[0]++;
                details.putIfAbsent(key, new String[]{ours != null ? pluginOf(ours.getClassName()) : "server", frame(stack[0])});
                examples.putIfAbsent(key, stack);
            }
            List<Map.Entry<String, int[]>> ranked = new ArrayList<>(counts.entrySet());
            ranked.sort(Comparator.comparingInt((Map.Entry<String, int[]> entry) -> entry.getValue()[0]).reversed());
            List<Culprit> culprits = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : ranked.subList(0, Math.min(4, ranked.size()))) {
                String[] detail = details.get(entry.getKey());
                culprits.add(new Culprit(entry.getKey(), detail[0], detail[1], entry.getValue()[0] / (double) samples.size()));
            }
            List<String> stack = new ArrayList<>();
            if (!ranked.isEmpty()) {
                StackTraceElement[] example = examples.get(ranked.getFirst().getKey());
                for (int i = 0; i < Math.min(STACK_LINES, example.length); i++) {
                    stack.add(example[i].toString());
                }
            }
            return new Spike(System.currentTimeMillis(), tick, ms, Bukkit.getOnlinePlayers().size(), gcMs,
                    cause(culprits), culprits, systems.subList(0, Math.min(6, systems.size())), stack, samples.size());
        }

        private String cause(List<Culprit> culprits) {
            if (gcMs >= ms * 0.5) {
                return String.format(Locale.ROOT, "Garbage-collection pause (%.0f ms)", gcMs);
            }
            PerfTracker.TickSection top = systems.isEmpty() ? null : systems.getFirst();
            if (top != null && top.ms() >= ms * 0.4) {
                return String.format(Locale.ROOT, "%s (%.0f ms)", top.system(), top.ms());
            }
            if (!culprits.isEmpty()) {
                Culprit first = culprits.getFirst();
                return first.where() + (first.plugin().equals("server") ? "" : " [" + first.plugin() + "]");
            }
            if (top != null && top.ms() >= 10) {
                return String.format(Locale.ROOT, "%s (%.0f ms), rest unsampled", top.system(), top.ms());
            }
            return "Unknown - over before sampling started";
        }

        /** The top frame that isn't the JDK's own plumbing - usually the real work. */
        private static StackTraceElement firstInteresting(StackTraceElement[] stack) {
            for (StackTraceElement frame : stack) {
                String name = frame.getClassName();
                if (!name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("sun.")) {
                    return frame;
                }
            }
            return stack[0];
        }
    }

    /** {@code PetDisplayService.tick:123} */
    static String frame(StackTraceElement frame) {
        String name = frame.getClassName();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return simple + "." + frame.getMethodName() + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
    }

    /** {@code me.dontshare.yieldpacks.x.Y} → {@code yield-packs}. */
    static String pluginOf(String className) {
        if (!className.startsWith("me.dontshare.")) {
            return "server";
        }
        String rest = className.substring("me.dontshare.".length());
        int dot = rest.indexOf('.');
        String segment = dot < 0 ? rest : rest.substring(0, dot);
        return segment.startsWith("yield") && segment.length() > 5 ? "yield-" + segment.substring(5) : segment;
    }
}
