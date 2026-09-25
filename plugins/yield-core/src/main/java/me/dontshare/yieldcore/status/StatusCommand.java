package me.dontshare.yieldcore.status;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.perf.PerfTracker;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.command.CommandSender;

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
 * their kind rather than the top few.
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
                }));
    }

    private static void summary(CommandSender sender) {
        ServerHealth health = ServerHealth.read();
        send(sender, HEADER + "<white>Server status</white> <dark_gray>(last " + PerfTracker.WINDOW_SECONDS + "s)</dark_gray>");
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
