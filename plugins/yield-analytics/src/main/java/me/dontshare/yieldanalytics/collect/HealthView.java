package me.dontshare.yieldanalytics.collect;

import me.dontshare.yieldanalytics.webhook.WebhookService;
import me.dontshare.yieldcore.diagnostics.DataHealth;
import me.dontshare.yieldcore.diagnostics.Diagnostics;
import me.dontshare.yieldcore.diagnostics.LeakScanner;
import me.dontshare.yieldcore.diagnostics.MemoryMonitor;
import me.dontshare.yieldcore.diagnostics.TickMonitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core's {@link Diagnostics} - lag spikes, memory, leaks, failed saves -
 * copied into plain maps on the main thread every few seconds, for the
 * website's health panel and the alert webhooks.
 */
public final class HealthView {

    private final WebhookService webhooks;
    private volatile Map<String, Object> snapshot = Map.of();

    public HealthView(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    public Map<String, Object> snapshot() {
        return snapshot;
    }

    /** Main thread. */
    public void refresh() {
        if (!Diagnostics.running()) {
            return;
        }
        List<Diagnostics.Finding> findings = Diagnostics.findings();
        webhooks.healthFindings(findings);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("t", System.currentTimeMillis());
        out.put("findings", findings.stream().map(finding -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("severity", finding.severity().name().toLowerCase(java.util.Locale.ROOT));
            row.put("key", finding.key());
            row.put("title", finding.title());
            row.put("detail", finding.detail());
            return row;
        }).toList());

        TickMonitor ticks = Diagnostics.ticks();
        out.put("spikeThresholdMs", TickMonitor.SPIKE_MS);
        out.put("spikesTotal", ticks.totalSpikes());
        out.put("spikes", ticks.spikes().stream().limit(60).map(spike -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("at", spike.at());
            row.put("ms", LiveMonitor.round(spike.ms(), 1));
            row.put("gcMs", LiveMonitor.round(spike.gcMs(), 1));
            row.put("online", spike.online());
            row.put("cause", spike.cause());
            row.put("samples", spike.samples());
            row.put("culprits", spike.culprits().stream().map(culprit -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("where", culprit.where());
                c.put("plugin", culprit.plugin());
                c.put("inside", culprit.inside());
                c.put("share", LiveMonitor.round(culprit.share(), 3));
                return c;
            }).toList());
            row.put("systems", spike.systems().stream().map(system -> Map.of("system", system.system(),
                    "ms", LiveMonitor.round(system.ms(), 2))).toList());
            row.put("stack", spike.stack());
            return row;
        }).toList());

        MemoryMonitor memory = Diagnostics.memory();
        MemoryMonitor.Trend trend = memory.trend();
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("maxMb", memory.maxMb());
        mem.put("liveMb", memory.lastLiveMb());
        mem.put("enoughData", trend.enoughData());
        mem.put("mbPerHour", LiveMonitor.round(trend.mbPerHour(), 1));
        mem.put("risingBuckets", trend.risingBuckets());
        mem.put("hoursToFull", Double.isInfinite(trend.hoursToFull()) ? null : LiveMonitor.round(trend.hoursToFull(), 1));
        mem.put("minutes", memory.minutes().stream().map(minute -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", minute.at());
            row.put("liveMb", minute.liveMb() < 0 ? null : minute.liveMb());
            row.put("collections", minute.collections());
            row.put("pauseMs", minute.pauseMs());
            row.put("longestPauseMs", minute.longestPauseMs());
            row.put("online", minute.online());
            return row;
        }).toList());
        mem.put("pauses", memory.longPauses().stream().limit(20).map(pause -> Map.of(
                "at", pause.at(), "collector", pause.collector(), "cause", pause.cause(), "ms", pause.ms(),
                "beforeMb", pause.beforeMb(), "afterMb", pause.afterMb())).toList());
        out.put("memory", mem);

        LeakScanner leaks = Diagnostics.leaks();
        LeakScanner.Scan scan = leaks.latest();
        Map<String, Object> leak = new LinkedHashMap<>();
        if (scan != null) {
            leak.put("at", scan.at());
            leak.put("durationMs", scan.durationMs());
            leak.put("objects", scan.objects());
            leak.put("complete", scan.complete());
            leak.put("scans", leaks.history().size());
            leak.put("suspects", leaks.suspects().stream().map(suspect -> Map.of("path", suspect.path(),
                    "plugin", suspect.plugin(), "problem", suspect.problem(), "detail", suspect.detail())).toList());
            List<Map<String, Object>> perPlayer = new ArrayList<>();
            List<Map<String, Object>> largest = new ArrayList<>();
            for (LeakScanner.Holder holder : scan.holders()) {
                if ((holder.kind().startsWith("per-player") || holder.leftPlayers() > 0) && perPlayer.size() < 25) {
                    perPlayer.add(holderRow(holder));
                }
                if (largest.size() < 15) {
                    largest.add(holderRow(holder));
                }
            }
            leak.put("perPlayer", perPlayer);
            leak.put("largest", largest);
            leak.put("gauges", scan.gauges());
        }
        out.put("leaks", leak);

        List<DataHealth.Failure> failures = DataHealth.since(0);
        out.put("saves", Map.of("failedTotal", DataHealth.total(), "recent", failures.stream().limit(20).map(failure -> Map.of(
                "at", failure.at(), "store", failure.store(), "player", String.valueOf(failure.player()),
                "reason", failure.reason())).toList()));
        snapshot = out;
    }

    private static Map<String, Object> holderRow(LeakScanner.Holder holder) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", holder.path());
        row.put("plugin", holder.plugin());
        row.put("kind", holder.kind());
        row.put("size", holder.size());
        row.put("stale", holder.stale());
        row.put("leftPlayers", holder.leftPlayers());
        row.put("instances", holder.instances());
        return row;
    }
}
