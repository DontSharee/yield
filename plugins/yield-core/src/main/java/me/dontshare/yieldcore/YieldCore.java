package me.dontshare.yieldcore;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.dontshare.yieldcore.chat.ChatFormatter;
import me.dontshare.yieldcore.command.AdminCommandRegistry;
import me.dontshare.yieldcore.command.AdminCommands;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.command.CommandPermissions;
import me.dontshare.yieldcore.command.YieldCommand;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.fakeblock.FakeBlockClickRegistry;
import me.dontshare.yieldcore.fakeblock.FakeBlockDigRegistry;
import me.dontshare.yieldcore.fakeblock.FakeFallingBlock;
import me.dontshare.yieldcore.gui.GuiListener;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.home.HomeCommand;
import me.dontshare.yieldcore.home.HomeService;
import me.dontshare.yieldcore.listener.ListenerManager;
import me.dontshare.yieldcore.packet.EntityClickRegistry;
import me.dontshare.yieldcore.placeholder.PlaceholderRegistry;
import me.dontshare.yieldcore.placeholder.YieldExpansion;
import me.dontshare.yieldcore.player.PlayerProfile;
import me.dontshare.yieldcore.player.PlayerProfileManager;
import me.dontshare.yieldcore.restrictions.GameplayRestrictionsListener;
import me.dontshare.yieldcore.scoreboard.ScoreboardManager;
import me.dontshare.yieldcore.scoreboard.YieldScoreboardDisplay;
import me.dontshare.yieldcore.spawn.SpawnCommand;
import me.dontshare.yieldcore.spawn.SpawnService;
import me.dontshare.yieldcore.teleport.BackCommand;
import me.dontshare.yieldcore.teleport.BackLocationService;
import me.dontshare.yieldcore.teleport.TeleportRequestService;
import me.dontshare.yieldcore.teleport.TpaCommand;
import me.dontshare.yieldcore.worldedit.SchematicService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

public final class YieldCore extends JavaPlugin {

    private static final long AUTOSAVE_INTERVAL_TICKS = 20L * 60 * 2; // 2 minutes

    private ListenerManager listenerManager;
    private DatabaseManager databaseManager;
    private ScoreboardManager scoreboardManager;
    private PlaceholderRegistry placeholderRegistry;
    private PlayerProfileManager playerProfileManager;
    private GuiManager guiManager;
    private SchematicService schematicService;
    private YieldScoreboardDisplay scoreboardDisplay;
    private FakeFallingBlock fakeFallingBlock;
    private ChatFormatter chatFormatter;
    private SpawnService spawnService;
    private HomeService homeService;
    private TeleportRequestService teleportRequestService;
    private BackLocationService backLocationService;
    private AdminCommandRegistry adminCommandRegistry;

    @Override
    public void onLoad() {
        // Must happen at onLoad, before other plugins finish loading -
        // PacketEvents hooks into the Netty pipeline and only one plugin
        // may own that lifecycle. Every other plugin that needs
        // PacketEvents depends on this one and just calls PacketEvents.getAPI()
        // directly rather than initializing it themselves.
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();
        EntityClickRegistry.install(this);
        FakeBlockClickRegistry.install(this);
        FakeBlockDigRegistry.install(this);
        fakeFallingBlock = new FakeFallingBlock(this);

        saveDefaultConfig();

        listenerManager = new ListenerManager(this);
        databaseManager = new DatabaseManager(getConfig());
        scoreboardManager = new ScoreboardManager();
        placeholderRegistry = new PlaceholderRegistry();

        PlayerDataStore<PlayerProfile> profileStore = new PlayerDataStore<>(
                databaseManager, "playerData", "core", PlayerProfile.class,
                playerId -> new PlayerProfile(playerId, "unknown"), getLogger());
        playerProfileManager = new PlayerProfileManager(profileStore, getLogger());
        listenerManager.register(playerProfileManager);
        profileStore.startAutoSave(this, AUTOSAVE_INTERVAL_TICKS);

        placeholderRegistry.register("username",
                player -> profileStore.getOrCreate(player.getUniqueId()).getUsername());

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new YieldExpansion(this, placeholderRegistry).register();
        }

        if (Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit") != null) {
            schematicService = new SchematicService(this);
        } else {
            getLogger().warning("FastAsyncWorldEdit not found - schematic pasting will not work.");
        }

        scoreboardDisplay = new YieldScoreboardDisplay(this, scoreboardManager);
        listenerManager.register(scoreboardDisplay);
        scoreboardDisplay.start();

        chatFormatter = new ChatFormatter();
        listenerManager.register(chatFormatter);

        listenerManager.register(new GameplayRestrictionsListener());
        GameplayRestrictionsListener.startHelmetReconcileTask(this);

        guiManager = new GuiManager();
        listenerManager.register(new GuiListener(guiManager, this));

        spawnService = new SpawnService(this, getLogger());
        CommandManager.register(this, SpawnCommand.spawn(spawnService), "Teleport to spawn");
        CommandManager.register(this, SpawnCommand.setSpawn(spawnService), "Set the server's spawn point to your current location");

        saveResource("home.yml", false);
        homeService = new HomeService(profileStore, YamlConfiguration.loadConfiguration(new File(getDataFolder(), "home.yml")));
        CommandManager.register(this, HomeCommand.sethome(homeService), "Set a named home at your current location");
        CommandManager.register(this, HomeCommand.home(homeService), "Teleport to one of your homes");
        CommandManager.register(this, HomeCommand.delhome(homeService), "Delete one of your homes");
        CommandManager.register(this, HomeCommand.homes(homeService), "List your homes");

        teleportRequestService = new TeleportRequestService(this);
        CommandManager.register(this, TpaCommand.tpa(teleportRequestService), "Request to teleport to another player");
        CommandManager.register(this, TpaCommand.tpahere(teleportRequestService), "Request another player to teleport to you");
        CommandManager.register(this, TpaCommand.tpaccept(teleportRequestService), "Accept a pending teleport request");
        CommandManager.register(this, TpaCommand.tpdeny(teleportRequestService), "Deny a pending teleport request");

        backLocationService = new BackLocationService();
        listenerManager.register(backLocationService);
        CommandManager.register(this, BackCommand.build(backLocationService), "Teleport to where you last teleported from (or died)");

        CommandManager.register(this, YieldCommand.build(this), "Yield admin commands", List.of("yld"));
        CommandManager.register(this, AdminCommands.gamemode("gmc", GameMode.CREATIVE), "Set gamemode to creative");
        CommandManager.register(this, AdminCommands.gamemode("gms", GameMode.SURVIVAL), "Set gamemode to survival");
        CommandManager.register(this, AdminCommands.gamemode("gmsp", GameMode.SPECTATOR), "Set gamemode to spectator");
        CommandManager.register(this, AdminCommands.gamemode("gma", GameMode.ADVENTURE), "Set gamemode to adventure");
        CommandManager.register(this, AdminCommands.fly(), "Toggle flight");

        adminCommandRegistry = new AdminCommandRegistry();
        // Deliberately NOT built/registered here - every other plugin still
        // needs a chance to call registry.register(...) from their own
        // onEnable first. Registering THIS handler (rather than calling
        // event.registrar() directly) defers the actual assembly to
        // yield-core's own COMMANDS lifecycle firing, by which point every
        // plugin loaded at server startup has already finished onEnable.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var builder = Commands.literal("admin").requires(CommandPermissions.permission("yield.admin"));
            for (var domain : adminCommandRegistry.domains()) {
                builder.then(domain);
            }
            event.registrar().register(builder.build(), "Yield admin commands");
        });
    }

    @Override
    public void onDisable() {
        // Hands back anything still sitting in a screen's editable slots
        // (the Forge grid, a candy part-way through being applied). Plugins
        // are disabled before players are kicked and saved, so the close
        // events a shutdown eventually fires arrive with no listener left -
        // without this, a restart or /reload destroys those items outright.
        if (guiManager != null) {
            guiManager.closeAll();
        }
        // Async tasks aren't guaranteed to finish during shutdown, so the
        // final save has to block rather than rely on the normal async path.
        if (playerProfileManager != null) {
            playerProfileManager.getStore().saveAllSync();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        if (schematicService != null) {
            schematicService.shutdown();
        }
        PacketEvents.getAPI().terminate();
    }

    public ListenerManager getListenerManager() {
        return listenerManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public PlaceholderRegistry getPlaceholderRegistry() {
        return placeholderRegistry;
    }

    public PlayerProfileManager getPlayerProfileManager() {
        return playerProfileManager;
    }

    public GuiManager getGuiManager() {
        return guiManager;
    }

    public YieldScoreboardDisplay getScoreboardDisplay() {
        return scoreboardDisplay;
    }

    /** Null if FastAsyncWorldEdit isn't installed - always check before using. */
    public SchematicService getSchematicService() {
        return schematicService;
    }

    public FakeFallingBlock getFakeFallingBlock() {
        return fakeFallingBlock;
    }

    public ChatFormatter getChatFormatter() {
        return chatFormatter;
    }

    public SpawnService getSpawnService() {
        return spawnService;
    }

    public HomeService getHomeService() {
        return homeService;
    }

    public TeleportRequestService getTeleportRequestService() {
        return teleportRequestService;
    }

    public BackLocationService getBackLocationService() {
        return backLocationService;
    }

    /** Register your own domain's "/admin &lt;domain&gt; ..." branch here during your plugin's onEnable - see AdminCommandRegistry's own doc for the full picture. */
    public AdminCommandRegistry getAdminCommandRegistry() {
        return adminCommandRegistry;
    }
}
