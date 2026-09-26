package me.dontshare.yieldloadtest;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.command.CommandPermissions;
import me.dontshare.yieldcore.status.StatusRegistry;
import me.dontshare.yieldcore.status.SyntheticPlayers;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldzones.YieldZones;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /loadtest} - fills the server with bot players that fight in the
 * zones like real ones, to see what a crowd actually costs before real
 * players find out.
 * <ul>
 *   <li>{@code spawn <count> [zone]} - bring the bot count up to {@code count}, all in one zone or spread over all</li>
 *   <li>{@code remove [count]} - bring it back down (to zero by default)</li>
 *   <li>{@code wander <share>} / {@code tap <share>} - what fraction of bots walk about / tap cubes</li>
 *   <li>{@code report} - the numbers, also logged as one {@code LOADTEST} line</li>
 *   <li>{@code auto <seconds> <zone|all> <counts...>} - step through the counts, holding each, reporting at the end of each, then remove the bots</li>
 *   <li>{@code cleanup} - delete every document a bot ever wrote</li>
 * </ul>
 * A test tool: the build never uploads it, and it has no business on a live server.
 */
public final class YieldLoadTest extends JavaPlugin {

    private static final String HEADER = "<#FF9F43><bold>LoadTest</bold></#FF9F43> <dark_gray>»</dark_gray> ";

    private LoadTestService service;
    private BukkitTask autoRun;

    @Override
    public void onEnable() {
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        YieldZones zones = JavaPlugin.getPlugin(YieldZones.class);
        service = new LoadTestService(this, packs, zones);
        service.start();
        CommandManager.register(this, command(), "Load-test the server with bot players", List.of());
        StatusRegistry.register("Load test", this::statusLines);
        getLogger().warning("yield-loadtest is enabled - a test tool, not for a live server.");
    }

    @Override
    public void onDisable() {
        StatusRegistry.unregister("Load test");
        if (autoRun != null) {
            autoRun.cancel();
        }
        if (service != null) {
            service.removeAll();
        }
    }

    private List<String> statusLines() {
        if (service.count() == 0 && service.queued() == 0) {
            return List.of();
        }
        double[] traffic = service.trafficPerSecond();
        List<String> lines = new ArrayList<>();
        lines.add("<white>" + service.count() + "</white> <gray>bots</gray>"
                + (service.queued() > 0 ? " <dark_gray>(" + service.queued() + " joining)</dark_gray>" : "")
                + " <dark_gray>·</dark_gray> <gray>" + service.botsByZone() + "</gray>");
        lines.add(String.format(Locale.ROOT,
                "<gray>to bots:</gray> <white>%,.0f</white> <gray>plugin pkt/s</gray> <white>%,.1f KB/s</white> "
                        + "<dark_gray>(%.2f KB/s each)</dark_gray> <white>%,.0f</white> <gray>server pkt/s</gray>",
                traffic[0], traffic[1] / 1024, traffic[1] / 1024 / Math.max(1, service.count()), traffic[2]));
        return lines;
    }

    /**
     * The leak drill: bots held on purpose after they leave, the two ways
     * plugins usually leak - Player objects and per-player keys - so the
     * leak scanner can be seen catching them.
     */
    private final List<Player> leakedPlayers = new ArrayList<>();
    private final Set<UUID> leakedIds = new HashSet<>();

    /** A deliberately slow tick, for checking the spike monitor names the culprit. */
    private static void simulateLag(int ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        double sink = 0;
        while (System.nanoTime() < end) {
            sink += Math.sqrt(sink + 1);
        }
        if (sink < 0) {
            Bukkit.getLogger().info("unreachable");
        }
    }

    private LiteralCommandNode<CommandSourceStack> command() {
        return Commands.literal("loadtest")
                .requires(CommandPermissions.permission("yieldloadtest.admin"))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, LoadTestService.MAX_BOTS))
                                .executes(ctx -> spawn(ctx, null))
                                .then(Commands.argument("zone", StringArgumentType.word())
                                        .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "zone"))))))
                .then(Commands.literal("remove")
                        .executes(ctx -> {
                            service.removeAll();
                            reply(ctx.getSource().getSender(), "<gray>All bots removed.</gray>");
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    service.removeDownTo(IntegerArgumentType.getInteger(ctx, "count"));
                                    reply(ctx.getSource().getSender(), "<gray>Now " + service.count() + " bots.</gray>");
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("wander")
                        .then(Commands.argument("share", DoubleArgumentType.doubleArg(0, 1))
                                .executes(ctx -> {
                                    service.setWanderShare(DoubleArgumentType.getDouble(ctx, "share"));
                                    reply(ctx.getSource().getSender(), "<gray>Wandering share " + service.wanderShare() + ".</gray>");
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("tap")
                        .then(Commands.argument("share", DoubleArgumentType.doubleArg(0, 1))
                                .executes(ctx -> {
                                    service.setTapShare(DoubleArgumentType.getDouble(ctx, "share"));
                                    reply(ctx.getSource().getSender(), "<gray>Tapping share " + service.tapShare() + ".</gray>");
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("report")
                        .executes(ctx -> {
                            report(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("auto")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 3600))
                                .then(Commands.argument("zone", StringArgumentType.word())
                                        .then(Commands.argument("steps", StringArgumentType.greedyString())
                                                .executes(this::auto)))))
                .then(Commands.literal("lag")
                        .then(Commands.argument("ms", IntegerArgumentType.integer(10, 20_000))
                                .executes(ctx -> {
                                    int ms = IntegerArgumentType.getInteger(ctx, "ms");
                                    Bukkit.getScheduler().runTask(this, () -> simulateLag(ms));
                                    reply(ctx.getSource().getSender(), "<gray>Next tick will take " + ms + " ms - check /yield status spikes.</gray>");
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("leak")
                        .executes(ctx -> {
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                if (SyntheticPlayers.is(player.getUniqueId())) {
                                    leakedPlayers.add(player);
                                    leakedIds.add(player.getUniqueId());
                                }
                            }
                            reply(ctx.getSource().getSender(), "<gray>Now holding " + leakedPlayers.size()
                                    + " bots after they leave - remove them and watch /yield status leaks.</gray>");
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("clear").executes(ctx -> {
                            leakedPlayers.clear();
                            leakedIds.clear();
                            reply(ctx.getSource().getSender(), "<gray>Leak test cleared.</gray>");
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("peek")
                        .then(Commands.argument("player", StringArgumentType.word()).then(Commands.argument("command", StringArgumentType.greedyString()).executes(ctx -> {
                            Player target = Bukkit.getPlayerExact(StringArgumentType.getString(ctx, "player"));
                            if (target != null) {
                                target.performCommand(StringArgumentType.getString(ctx, "command"));
                            }
                            CommandSender sender = ctx.getSource().getSender();
                            if (target == null) {
                                reply(sender, "<red>Not online.</red>");
                                return Command.SINGLE_SUCCESS;
                            }
                            // What their open menu shows, codes and all - for checking designs.
                            var serializer = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();
                            var view = target.getOpenInventory();
                            getLogger().info("PEEK title: " + serializer.serialize(view.title()));
                            var top = view.getTopInventory();
                            for (int slot = 0; slot < top.getSize(); slot++) {
                                var item = top.getItem(slot);
                                if (item == null || item.getType().isAir()) {
                                    continue;
                                }
                                var meta = item.getItemMeta();
                                getLogger().info("PEEK " + slot + " x" + item.getAmount() + " " + item.getType()
                                        + " | " + (meta != null && meta.hasDisplayName() ? serializer.serialize(meta.displayName()) : "-"));
                                if (meta != null && meta.lore() != null) {
                                    for (var line : meta.lore()) {
                                        getLogger().info("PEEK      " + serializer.serialize(line));
                                    }
                                }
                            }
                            return Command.SINGLE_SUCCESS;
                        }))))
                .then(Commands.literal("cleanup")
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (service.count() > 0) {
                                reply(sender, "<red>Remove the bots first - they would just write their data back.</red>");
                                return Command.SINGLE_SUCCESS;
                            }
                            service.cleanupData(() -> reply(sender, "<gray>Bot data deleted.</gray>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private int spawn(CommandContext<CommandSourceStack> ctx, String zone) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        if (zone != null && !JavaPlugin.getPlugin(YieldZones.class).getZones().containsKey(zone)) {
            reply(ctx.getSource().getSender(), "<red>No zone '" + zone + "'.</red>");
            return Command.SINGLE_SUCCESS;
        }
        service.scaleTo(count, zone);
        reply(ctx.getSource().getSender(), "<gray>Scaling to <white>" + count + "</white> bots"
                + (zone != null ? " in <white>" + zone + "</white>" : " across every zone") + ".</gray>");
        return Command.SINGLE_SUCCESS;
    }

    private void report(CommandSender sender) {
        String line = service.reportLine();
        getLogger().info(line);
        for (String status : statusLines()) {
            reply(sender, status);
        }
        reply(sender, "<gray>" + line + "</gray>");
    }

    /** Steps through the counts, holding each, reporting at the end of each hold; then removes the bots. */
    private int auto(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String zoneArg = StringArgumentType.getString(ctx, "zone");
        String zone = zoneArg.equalsIgnoreCase("all") ? null : zoneArg;
        if (zone != null && !JavaPlugin.getPlugin(YieldZones.class).getZones().containsKey(zone)) {
            reply(sender, "<red>No zone '" + zone + "' - or use \"all\".</red>");
            return Command.SINGLE_SUCCESS;
        }
        List<Integer> steps = new ArrayList<>();
        try {
            for (String part : StringArgumentType.getString(ctx, "steps").split("[,\\s]+")) {
                if (!part.isBlank()) {
                    steps.add(Math.min(LoadTestService.MAX_BOTS, Integer.parseInt(part.trim())));
                }
            }
        } catch (NumberFormatException e) {
            steps.clear();
        }
        if (steps.isEmpty()) {
            reply(sender, "<red>Give the bot counts to step through, e.g. 25 50 100.</red>");
            return Command.SINGLE_SUCCESS;
        }
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        if (autoRun != null) {
            autoRun.cancel();
        }
        int[] step = {0};
        long[] stepStartedTick = {Bukkit.getCurrentTick()};
        service.scaleTo(steps.getFirst(), zone);
        getLogger().info("LOADTEST auto run: steps=" + steps + " hold=" + seconds + "s zone=" + (zone == null ? "all" : zone));
        autoRun = Bukkit.getScheduler().runTaskTimer(this, () -> {
            boolean settled = service.queued() == 0;
            if (!settled || Bukkit.getCurrentTick() - stepStartedTick[0] < seconds * 20L) {
                return;
            }
            report(sender);
            step[0]++;
            if (step[0] >= steps.size()) {
                service.removeAll();
                getLogger().info("LOADTEST auto run finished.");
                autoRun.cancel();
                autoRun = null;
                return;
            }
            service.scaleTo(steps.get(step[0]), zone);
            stepStartedTick[0] = Bukkit.getCurrentTick();
        }, 20L, 20L);
        reply(sender, "<gray>Auto run started: " + steps + ", " + seconds + "s each.</gray>");
        return Command.SINGLE_SUCCESS;
    }

    private static void reply(CommandSender sender, String miniMessage) {
        sender.sendMessage(Text.parse(HEADER + miniMessage));
    }

    Map<String, Integer> botsByZone() {
        return service.botsByZone();
    }
}
