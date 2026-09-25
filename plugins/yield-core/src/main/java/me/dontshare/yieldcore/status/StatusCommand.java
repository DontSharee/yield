package me.dontshare.yieldcore.status;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.diagnostics.Diagnostics;
import me.dontshare.yieldcore.diagnostics.LeakScanner;
import me.dontshare.yieldcore.diagnostics.MemoryMonitor;
import me.dontshare.yieldcore.diagnostics.TickMonitor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import me.dontshare.yieldcore.perf.PerfTracker;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code /yield status} - the server's health on one screen: tick rate and
 * tick time, memory, the database's backlog, what every timed system costs
 * per tick, what the plugins send, and whatever sections other plugins add
 * (see {@link StatusRegistry}).
 * <p>
 * {@code systems}, {@code packets} and {@code stores} list everything of
 * their kind rather than the top few. {@code health}, {@code spikes},
 * {@code memory} and {@code leaks} show what the {@link Diagnostics} have
 * found.
 */
public final class StatusCommand {

    private static final String HEADER = "<#4BD9FF><bold>Yield</bold></#4BD9FF> <dark_gray>»</dark_gray> ";
    private static final int TOP = 8;

    private StatusCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("status")
                .executes(ctx -> {
                    summary(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("systems").executes(ctx -> {
                    systems(ctx.getSource().getSender(), Integer.MAX_VALUE);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("packets").executes(ctx -> {
                    packets(ctx.getSource().getSender(), Integer.MAX_VALUE);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("stores").executes(ctx -> {
                    stores(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("health").executes(ctx -> {
                    health(ctx.getSource().getSender(), Integer.MAX_VALUE);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("spikes").executes(ctx -> {
                    spikes(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("memory").executes(ctx -> {
                    memory(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("leaks").executes(ctx -> {
                    leaks(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static void summary(CommandSender sender) {
        ServerHealth health = ServerHealth.read();
        send(sender, HEADER + "<white>Server status</white> <dark_gray>(last " + PerfTracker.WINDOW_SECONDS + "s)</dark_gray>");
        health(sender, 4);
        send(sender, "<gray>TPS</gray> " + tps(health.tps1m()) + " <dark_gray>/</dark_gray> " + tps(health.tps5m())
                + " <dark_gray>/</dark_gray> " + tps(health.tps15m())
                + "  <gray>MSPT</gray> " + mspt(health.msptAvg()) + " <dark_gray>avg</dark_gray> "
                + mspt(health.msptP95()) + " <dark_gray>p95</dark_gray> " + mspt(health.msptMax()) + " <dark_gray>max</dark_gray>");
        long heapPercent = health.heapMaxMb() == 0 ? 0 : health.heapUsedMb() * 100 / health.heapMaxMb();
        send(sender, "<gray>Memory</gray> <white>" + health.heapUsedMb() + "</white><dark_gray>/</dark_gray><white>"
                + health.heapMaxMb() + " MB</white> <dark_gray>(" + heapPercent + "%)</dark_gray>  <gray>Online</gray> <white>"
                + health.online() + "</white>");
        send(sender, "<gray>Database</gray> " + backlog(health.dbQueued()) + " <dark_gray>queued,</dark_gray> <white>"
                + health.dbActive() + "</white> <dark_gray>running</dark_gray>  <gray>Records</gray> <white>"
                + health.cachedRecords() + "</white> <dark_gray>cached,</dark_gray> <white>" + health.writesInFlight()
                + "</white> <dark_gray>writing</dark_gray>");
        send(sender, "<gray>Tracked systems</gray> " + mspt(health.trackedMsPerTick()) + " <dark_gray>per tick</dark_gray>  "
                + "<gray>Plugin packets</gray> <white>" + String.format(Locale.ROOT, "%,.0f", health.packetsPerSecond())
                + "/s</white>");
        systems(sender, TOP);
        packets(sender, 5);
        for (Map.Entry<String, Supplier<List<String>>> section : StatusRegistry.sections().entrySet()) {
            List<String> lines;
            try {
                lines = section.getValue().get();
            } catch (RuntimeException e) {
                lines = List.of("<red>failed: " + e.getMessage() + "</red>");
            }
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            send(sender, "<#4BD9FF>" + section.getKey() + "</#4BD9FF>");
            for (String line : lines) {
                send(sender, " " + line);
            }
        }
    }

    private static void systems(CommandSender sender, int limit) {
        List<PerfTracker.SectionStats> sections = PerfTracker.sections();
        send(sender, "<#4BD9FF>Systems</#4BD9FF> <dark_gray>ms/tick · avg/run · max/run · runs</dark_gray>");
        int shown = 0;
        for (PerfTracker.SectionStats stats : sections) {
            if (shown++ >= limit) {
                send(sender, " <dark_gray>... " + (sections.size() - limit) + " more - /yield status systems</dark_gray>");
                break;
            }
            send(sender, String.format(Locale.ROOT,
                    " <white>%s</white> %s <dark_gray>· %.3f · %.2f · %,d</dark_gray>",
                    stats.system(), mspt(stats.msPerTick()), stats.avgMsPerRun(), stats.maxMsPerRun(), stats.runs()));
        }
    }

    private static void packets(CommandSender sender, int limit) {
        list(sender, "Plugin packets by type", PerfTracker.packets(), limit);
        list(sender, "Plugin packets by system", PerfTracker.packetsBySystem(), limit);
    }

    private static void list(CommandSender sender, String title, List<PerfTracker.PacketStats> packets, int limit) {
        send(sender, "<#4BD9FF>" + title + "</#4BD9FF> <dark_gray>per second · since boot</dark_gray>");
        int shown = 0;
        for (PerfTracker.PacketStats stats : packets) {
            if (shown++ >= limit) {
                send(sender, " <dark_gray>... " + (packets.size() - limit) + " more - /yield status packets</dark_gray>");
                break;
            }
            send(sender, String.format(Locale.ROOT, " <white>%s</white> <gray>%,.1f/s</gray> <dark_gray>· %,d</dark_gray>",
                    stats.type(), stats.perSecond(), stats.total()));
        }
    }

    private static void stores(CommandSender sender) {
        send(sender, "<#4BD9FF>Player data stores</#4BD9FF> <dark_gray>cached · writing · queued this tick</dark_gray>");
        for (PlayerDataStore.StoreStats store : PlayerDataStore.allStats()) {
            send(sender, String.format(Locale.ROOT, " <white>%s</white> <gray>%d · %d · %d</gray>",
                    store.field(), store.cached(), store.writesInFlight(), store.coalesced()));
        }
    }

    // ------------------------------------------------------------------ diagnostics

    private static void health(CommandSender sender, int limit) {
        if (!Diagnostics.running()) {
            return;
        }
        List<Diagnostics.Finding> findings = Diagnostics.findings();
        if (findings.isEmpty()) {
            send(sender, "<gray>Health</gray> <green>✔ No problems found</green> <dark_gray>- lag spikes, memory, leaks and saves all look normal</dark_gray>");
            return;
        }
        send(sender, "<gray>Health</gray> <dark_gray>- hover for details</dark_gray>");
        int shown = 0;
        for (Diagnostics.Finding finding : findings) {
            if (shown++ >= limit) {
                send(sender, " <dark_gray>... " + (findings.size() - limit) + " more - /yield status health</dark_gray>");
                break;
            }
            String dot = switch (finding.severity()) {
                case CRITICAL -> "<red>●</red>";
                case WARN -> "<gold>●</gold>";
                case INFO -> "<gray>●</gray>";
            };
            if (limit == Integer.MAX_VALUE) {
                send(sender, " " + dot + " <white>" + esc(finding.title()) + "</white>");
                send(sender, "   <gray>" + esc(finding.detail()) + "</gray>");
            } else {
                sender.sendMessage(Text.parse(" " + dot + " <white>" + esc(finding.title()) + "</white>")
                        .hoverEvent(HoverEvent.showText(Component.text(finding.detail()))));
            }
        }
    }

    private static void spikes(CommandSender sender) {
        TickMonitor ticks = Diagnostics.ticks();
        List<TickMonitor.Spike> spikes = ticks.spikes();
        send(sender, HEADER + "<white>Lag spikes</white> <dark_gray>- ticks over " + (int) TickMonitor.SPIKE_MS
                + " ms, " + ticks.totalSpikes() + " since start. Hover one for its stack.</dark_gray>");
        if (spikes.isEmpty()) {
            send(sender, " <green>None yet.</green>");
            return;
        }
        for (TickMonitor.Spike spike : spikes.subList(0, Math.min(12, spikes.size()))) {
            String color = spike.ms() >= 1000 ? "<red>" : spike.ms() >= 250 ? "<gold>" : "<yellow>";
            List<String> hover = new ArrayList<>();
            hover.add(String.format(Locale.ROOT, "Tick %d · %.0f ms · %d online · GC %.0f ms · %d samples",
                    spike.tick(), spike.ms(), spike.online(), spike.gcMs(), spike.samples()));
            for (TickMonitor.Culprit culprit : spike.culprits()) {
                hover.add(String.format(Locale.ROOT, "%3.0f%%  %s  (in %s)", culprit.share() * 100, culprit.where(), culprit.inside()));
            }
            if (!spike.systems().isEmpty()) {
                hover.add("Timed systems this tick:");
                for (PerfTracker.TickSection system : spike.systems()) {
                    hover.add(String.format(Locale.ROOT, "  %s %.1f ms", system.system(), system.ms()));
                }
            }
            if (!spike.stack().isEmpty()) {
                hover.add("Stack:");
                for (String line : spike.stack()) {
                    hover.add("  " + line);
                }
            }
            sender.sendMessage(Text.parse(String.format(Locale.ROOT,
                            " <dark_gray>%s</dark_gray> %s%.0f ms</%s <white>%s</white>",
                            Diagnostics.clock(spike.at()), color, spike.ms(), color.substring(1), esc(spike.cause())))
                    .hoverEvent(HoverEvent.showText(Component.text(String.join("\n", hover)))));
        }
        send(sender, " <dark_gray>For a full CPU profile over time, Paper's built-in /spark profiler.</dark_gray>");
    }

    private static void memory(CommandSender sender) {
        MemoryMonitor memory = Diagnostics.memory();
        MemoryMonitor.Trend trend = memory.trend();
        ServerHealth health = ServerHealth.read();
        send(sender, HEADER + "<white>Memory</white>");
        send(sender, " <gray>Heap now</gray> <white>" + health.heapUsedMb() + " MB</white> <dark_gray>of " + health.heapMaxMb()
                + " MB - includes garbage waiting to be collected</dark_gray>");
        long live = memory.lastLiveMb();
        send(sender, " <gray>In use after last collection</gray> <white>" + (live < 0 ? "-" : live + " MB") + "</white>"
                + " <dark_gray>- the part that's actually kept</dark_gray>");
        if (trend.enoughData()) {
            String color = trend.risingBuckets() >= 4 && trend.mbPerHour() > 0 ? "<gold>" : "<green>";
            send(sender, String.format(Locale.ROOT, " <gray>Trend (last hour)</gray> %s%+.0f MB/hour</%s <dark_gray>- rising %d of the last 15-minute periods in a row, players %d → %d</dark_gray>",
                    color, trend.mbPerHour(), color.substring(1), trend.risingBuckets(), trend.onlineStart(), trend.onlineNow()));
        } else {
            send(sender, " <gray>Trend</gray> <dark_gray>needs about 75 minutes of uptime to judge</dark_gray>");
        }
        List<MemoryMonitor.Minute> minutes = memory.minutes();
        long collections = 0;
        long paused = 0;
        long longest = 0;
        for (int i = Math.max(0, minutes.size() - 5); i < minutes.size(); i++) {
            collections += minutes.get(i).collections();
            paused += minutes.get(i).pauseMs();
            longest = Math.max(longest, minutes.get(i).longestPauseMs());
        }
        send(sender, " <gray>Collections (last 5 min)</gray> <white>" + collections + "</white> <dark_gray>· paused</dark_gray> <white>"
                + paused + " ms</white> <dark_gray>· longest</dark_gray> <white>" + longest + " ms</white>");
        for (MemoryMonitor.Pause pause : memory.longPauses().subList(0, Math.min(5, memory.longPauses().size()))) {
            send(sender, "  <dark_gray>" + Diagnostics.clock(pause.at()) + "</dark_gray> <gold>" + pause.ms() + " ms</gold> <gray>"
                    + esc(pause.collector()) + " (" + esc(pause.cause()) + ")</gray>");
        }
    }

    private static void leaks(CommandSender sender) {
        LeakScanner scanner = Diagnostics.leaks();
        LeakScanner.Scan scan = scanner.scanNow();
        send(sender, HEADER + "<white>Leak scan</white> <dark_gray>- " + scan.objects() + " objects in " + scan.durationMs() + " ms"
                + (scan.complete() ? "" : ", stopped at the size limit") + "</dark_gray>");
        List<LeakScanner.Suspect> suspects = scanner.suspects();
        if (suspects.isEmpty()) {
            send(sender, " <green>✔ No leaks found.</green> <dark_gray>Growth needs a few scans (every 5 min) to show up.</dark_gray>");
        }
        for (LeakScanner.Suspect suspect : suspects) {
            send(sender, " <gold>●</gold> <white>" + esc(suspect.problem()) + "</white> <gray>" + esc(suspect.path())
                    + "</gray> <dark_gray>[" + esc(suspect.plugin()) + "]</dark_gray>");
            send(sender, "   <gray>" + esc(suspect.detail()) + "</gray>");
        }
        send(sender, "<#4BD9FF>Per-player data</#4BD9FF> <dark_gray>entries · belonging to players who left " + LeakScanner.graceText() + "+ ago</dark_gray>");
        int shown = 0;
        for (LeakScanner.Holder holder : scan.holders()) {
            if (!holder.kind().startsWith("per-player") && holder.leftPlayers() == 0) {
                continue;
            }
            if (shown++ >= 10) {
                break;
            }
            String staleColor = holder.stale() > 0 || holder.leftPlayers() > 0 ? "<gold>" : "<green>";
            send(sender, String.format(Locale.ROOT, " <white>%s</white> <dark_gray>[%s]</dark_gray> <gray>%,d</gray> · %s%,d</%s",
                    esc(holder.path()), esc(holder.plugin()), holder.size(), staleColor, holder.stale() + holder.leftPlayers(), staleColor.substring(1)));
        }
        send(sender, "<#4BD9FF>Largest collections</#4BD9FF>");
        for (LeakScanner.Holder holder : scan.holders().subList(0, Math.min(6, scan.holders().size()))) {
            send(sender, String.format(Locale.ROOT, " <white>%s</white> <dark_gray>[%s]</dark_gray> <gray>%,d</gray>",
                    esc(holder.path()), esc(holder.plugin()), holder.size()));
        }
        List<Map.Entry<String, Long>> gauges = new ArrayList<>(scan.gauges().entrySet());
        gauges.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, Long> gauge : gauges.subList(0, Math.min(8, gauges.size()))) {
            line.append(" <gray>").append(esc(gauge.getKey())).append("</gray> <white>").append(gauge.getValue()).append("</white>");
        }
        send(sender, "<#4BD9FF>Tasks, entities, chunks</#4BD9FF>" + line);
    }

    private static String esc(String text) {
        return MiniMessage.miniMessage().escapeTags(text);
    }

    private static String tps(double tps) {
        String color = tps >= 19.5 ? "<green>" : tps >= 17 ? "<yellow>" : "<red>";
        return color + String.format(Locale.ROOT, "%.1f", tps) + "</" + color.substring(1);
    }

    private static String mspt(double ms) {
        String color = ms < 25 ? "<green>" : ms < 45 ? "<yellow>" : "<red>";
        return color + String.format(Locale.ROOT, "%.2f", ms) + "</" + color.substring(1);
    }

    private static String backlog(int queued) {
        String color = queued < 10 ? "<green>" : queued < 100 ? "<yellow>" : "<red>";
        return color + queued + "</" + color.substring(1);
    }

    private static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(Text.parse(miniMessage));
    }
}
