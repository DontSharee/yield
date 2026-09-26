package me.dontshare.yieldanalytics;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldanalytics.collect.GameEventCounter;
import me.dontshare.yieldanalytics.collect.LiveMonitor;
import me.dontshare.yieldanalytics.collect.SessionTracker;
import me.dontshare.yieldanalytics.collect.StatsJob;
import me.dontshare.yieldanalytics.collect.TutorialSteps;
import me.dontshare.yieldanalytics.data.ActivityStore;
import me.dontshare.yieldanalytics.data.AnalyticsProfile;
import me.dontshare.yieldanalytics.web.ApiRoutes;
import me.dontshare.yieldanalytics.web.WebServer;
import me.dontshare.yieldanalytics.webhook.ErrorLogHandler;
import me.dontshare.yieldanalytics.webhook.WebhookService;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.command.CommandPermissions;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldcore.perf.PerfTracker;
import me.dontshare.yieldcore.status.StatusRegistry;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldzones.YieldZones;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player analytics: collects what players do, works out what it means, and
 * serves it as a dashboard website and JSON API - with webhooks for the
 * things worth hearing about as they happen.
 * <p>
 * See analytics.yml for the port, who may view and edit, and the webhooks, and
 * {@code /analytics} for the address and a test post.
 */
public final class YieldAnalytics extends JavaPlugin {

    private static final String HEADER = "<#4BD9FF><bold>Analytics</bold></#4BD9FF> <dark_gray>»</dark_gray> ";

    private AnalyticsConfig config;
    private PlayerDataStore<AnalyticsProfile> store;
    private ActivityStore activity;
    private SessionTracker sessions;
    private LiveMonitor live;
    private me.dontshare.yieldanalytics.collect.HealthView health;
    private StatsJob stats;
    private WebhookService webhooks;
    private WebServer web;
    private ErrorLogHandler errorHandler;
    private me.dontshare.yieldanalytics.web.Access access;

    @Override
    public void onEnable() {
        config = AnalyticsConfig.load(this);
        me.dontshare.yieldanalytics.collect.Exclusions.setCountBots(config.countLoadTestBots());
        if (config.countLoadTestBots()) {
            getLogger().warning("count-load-test-bots is on - load-test bots are being recorded as players.");
        }
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        YieldZones zones = JavaPlugin.getPlugin(YieldZones.class);
        DatabaseManager database = core.getDatabaseManager();

        store = PlayerStores.register(this, core.getListenerManager(), database, "analytics",
                AnalyticsProfile.class, AnalyticsProfile::new, "analytics data");
        activity = new ActivityStore(database, getLogger());
        database.supplyAsync(() -> {
            activity.ensureIndexes(config.retentionDays());
            return null;
        });
        webhooks = new WebhookService(config, getLogger());
        TutorialSteps tutorial = new TutorialSteps();
        tutorial.reload();

        sessions = new SessionTracker(store, core.getPlayerProfileManager().getStore(), activity, database,
                tutorial, zones, webhooks);
        core.getListenerManager().register(sessions);
        core.getListenerManager().register(new GameEventCounter(activity, packs));

        live = new LiveMonitor(core.getPlayerProfileManager().getStore(), packs, zones, sessions, tutorial,
                activity, database, webhooks);
        database.supplyAsync(() -> {
            live.loadPeaks();
            return null;
        });
        health = new me.dontshare.yieldanalytics.collect.HealthView(webhooks);
        stats = new StatsJob(database, activity, packs, zones, tutorial, getLogger(), config.leftAfterDays());

        var scheduler = Bukkit.getScheduler();
        scheduler.runTaskTimer(this, PerfTracker.timed("analytics.live", () -> {
            live.refresh();
            sessions.watchTutorial();
        }), 20L, 40L);
        scheduler.runTaskTimer(this, PerfTracker.timed("analytics.health", health::refresh), 100L, 200L);
        scheduler.runTaskTimer(this, PerfTracker.timed("analytics.minute", () -> {
            live.minute();
            webhooks.flushNewPlayers();
        }), 1200L, 1200L);
        scheduler.runTaskTimer(this, stats::refreshAsync, 200L, config.statsRefreshMinutes() * 1200L);
        scheduler.runTaskTimer(this, this::summary, config.summaryIntervalMinutes() * 1200L,
                config.summaryIntervalMinutes() * 1200L);

        access = new me.dontshare.yieldanalytics.web.Access(config.whitelist(), config.trustedProxies(), config.viewerCodeMinutes());
        me.dontshare.yieldanalytics.admin.PlayerDirectory directory = new me.dontshare.yieldanalytics.admin.PlayerDirectory(activity, this::onlineIds);
        me.dontshare.yieldanalytics.admin.EditService editService = new me.dontshare.yieldanalytics.admin.EditService(
                this, store, activity, directory, webhooks);
        database.supplyAsync(() -> {
            editService.ensureIndexes();
            return null;
        });
        if (config.webEnabled()) {
            ApiRoutes routes = new ApiRoutes(live, stats, activity, packs, health);
            routes.setAdmin(editService, directory, access);
            web = new WebServer(config, routes, access, getLogger());
            try {
                web.start();
                getLogger().info("Analytics dashboard on port " + config.port() + " - /analytics for the address and viewer code.");
                if (config.whitelist().isEmpty()) {
                    getLogger().info("No whitelisted addresses yet, so nobody can edit players on the site - /analytics whitelist add <ip>.");
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Could not start the analytics website on " + config.bind() + ":" + config.port(), e);
                web = null;
            }
        }

        if (config.webhooks().stream().anyMatch(target -> target.wants("error"))) {
            errorHandler = new ErrorLogHandler(webhooks);
            Logger.getLogger("").addHandler(errorHandler);
        }
        // Once everything else has enabled too.
        scheduler.runTask(this, () -> webhooks.serverStarted(zones.getZones().size(),
                (int) java.util.Arrays.stream(Bukkit.getPluginManager().getPlugins())
                        .filter(plugin -> plugin.getName().startsWith("yield-")).count()));

        StatusRegistry.register("Analytics", this::statusLines);
        CommandManager.register(this, command(), "Player analytics dashboard", List.of());
    }

    @Override
    public void onDisable() {
        StatusRegistry.unregister("Analytics");
        if (errorHandler != null) {
            Logger.getLogger("").removeHandler(errorHandler);
        }
        if (webhooks != null) {
            webhooks.serverStopping(Bukkit.getOnlinePlayers().size());
        }
        if (sessions != null) {
            sessions.shutdown();
        }
        if (store != null) {
            store.saveAllSync();
        }
        if (activity != null) {
            activity.flush();
        }
        if (web != null) {
            web.stop();
        }
    }

    private void summary() {
        Object players = stats.snapshot().get("players");
        int total = players instanceof Map<?, ?> map && map.get("total") instanceof Number number ? number.intValue() : 0;
        Object online = live.snapshot().get("online");
        webhooks.summary(activity.drainPeriod(), online instanceof Number number ? number.intValue() : 0, total);
    }

    private String address() {
        String host = config.bind().equals("0.0.0.0") ? Bukkit.getIp().isBlank() ? "<server-ip>" : Bukkit.getIp() : config.bind();
        return "http://" + host + ":" + config.port() + "/";
    }

    private List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add(web != null
                ? "<gray>Dashboard</gray> <white>" + address() + "</white> <dark_gray>·</dark_gray> <gray>"
                + web.requestsServed() + " requests</gray>"
                : "<gray>Dashboard</gray> <red>off</red>");
        Object generated = stats.snapshot().get("generatedAt");
        lines.add("<gray>Stats</gray> " + (generated instanceof Number at
                ? "<white>" + ((System.currentTimeMillis() - at.longValue()) / 1000) + "s ago</white>"
                : "<yellow>not run yet</yellow>")
                + " <dark_gray>·</dark_gray> <gray>webhooks</gray> <white>" + webhooks.targetCount() + "</white>");
        return lines;
    }

    private LiteralCommandNode<CommandSourceStack> command() {
        return Commands.literal("analytics")
                .requires(CommandPermissions.permission("yieldanalytics.admin"))
                .executes(ctx -> {
                    info(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("code")
                        .executes(ctx -> {
                            showCode(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("new").executes(ctx -> {
                            access.rotate();
                            reply(ctx.getSource().getSender(), "<gray>New viewer code - everyone on the old one has been signed out.</gray>");
                            showCode(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("whitelist")
                        .then(Commands.literal("list").executes(ctx -> {
                            listWhitelist(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("add")
                                .then(Commands.argument("entry", StringArgumentType.greedyString()).executes(ctx -> {
                                    changeWhitelist(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "entry"), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("entry", StringArgumentType.greedyString()).executes(ctx -> {
                                    changeWhitelist(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "entry"), false);
                                    return Command.SINGLE_SUCCESS;
                                }))))
                .then(Commands.literal("refresh").executes(ctx -> {
                    stats.refreshAsync();
                    reply(ctx.getSource().getSender(), "<gray>Recomputing the player statistics now.</gray>");
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("webhooktest").executes(ctx -> {
                    int sent = webhooks.test(ctx.getSource().getSender().getName());
                    reply(ctx.getSource().getSender(), sent == 0
                            ? "<yellow>No webhook targets are enabled in analytics.yml.</yellow>"
                            : "<gray>Test sent to <white>" + sent + "</white> target(s).</gray>");
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    private void info(CommandSender sender) {
        for (String line : statusLines()) {
            reply(sender, line);
        }
        reply(sender, "<gray>/analytics code [new] · whitelist add|remove|list · refresh · webhooktest</gray>");
    }

    private java.util.Set<java.util.UUID> onlineIds() {
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        if (live.snapshot().get("players") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map && map.get("uuid") != null) {
                    ids.add(java.util.UUID.fromString(String.valueOf(map.get("uuid"))));
                }
            }
        }
        return ids;
    }

    private void showCode(CommandSender sender) {
        String code = access.code();
        long minutes = Math.max(0, (access.codeExpiresAt() - System.currentTimeMillis()) / 60_000L);
        sender.sendMessage(Text.parse(HEADER + "<gray>Viewer code (click to copy):</gray> <white><bold><code></bold></white>"
                        + " <dark_gray>- read-only, changes in " + (minutes < 1 ? "under a minute" : minutes + " min") + "</dark_gray>",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("code",
                        net.kyori.adventure.text.Component.text(code).clickEvent(ClickEvent.copyToClipboard(code)))));
    }

    private void listWhitelist(CommandSender sender) {
        List<String> entries = access.whitelist();
        if (entries.isEmpty()) {
            reply(sender, "<gray>Nobody is whitelisted - the site is read-only for everyone. /analytics whitelist add <ip> [= name]</gray>");
            return;
        }
        reply(sender, "<gray>Whitelisted (can edit, no code needed):</gray>");
        for (String entry : entries) {
            sender.sendMessage(Text.parse(" <white>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(entry) + "</white>"));
        }
    }

    private void changeWhitelist(CommandSender sender, String raw, boolean add) {
        List<String> entries = new ArrayList<>(access.whitelist());
        String entry;
        try {
            entry = me.dontshare.yieldanalytics.web.Access.validate(raw);
        } catch (IllegalArgumentException e) {
            reply(sender, "<red>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(e.getMessage()) + "</red>");
            return;
        }
        String address = entry.split("=")[0].trim();
        boolean had = entries.removeIf(existing -> existing.split("=")[0].trim().equals(address));
        if (add) {
            entries.add(entry);
        } else if (!had) {
            reply(sender, "<yellow>" + address + " wasn't on the whitelist.</yellow>");
            return;
        }
        access.setWhitelist(entries);
        try {
            AnalyticsConfig.saveWhitelist(this, entries);
        } catch (java.io.IOException e) {
            getLogger().log(Level.WARNING, "Could not save the whitelist to analytics.yml", e);
            reply(sender, "<yellow>Applied, but couldn't be saved to analytics.yml - it lasts until restart.</yellow>");
        }
        reply(sender, add ? "<green>" + address + " can now open the site and edit players.</green>"
                : "<gray>" + address + " is no longer whitelisted.</gray>");
    }

    private static void reply(CommandSender sender, String miniMessage) {
        sender.sendMessage(Text.parse(HEADER + miniMessage));
    }
}
