package me.dontshare.yield;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import me.dontshare.yield.chat.ChatFormatter;
import me.dontshare.yield.command.AdminCommands;
import me.dontshare.yield.command.CommandManager;
import me.dontshare.yield.command.YieldCommand;
import me.dontshare.yield.database.DatabaseManager;
import me.dontshare.yield.database.PlayerDataStore;
import me.dontshare.yield.gui.GuiListener;
import me.dontshare.yield.gui.GuiManager;
import me.dontshare.yield.listener.ListenerManager;
import me.dontshare.yield.placeholder.PlaceholderRegistry;
import me.dontshare.yield.placeholder.YieldExpansion;
import me.dontshare.yield.player.PlayerProfile;
import me.dontshare.yield.player.PlayerProfileManager;
import me.dontshare.yield.scoreboard.ScoreboardManager;
import me.dontshare.yield.scoreboard.YieldScoreboardDisplay;
import me.dontshare.yield.worldedit.SchematicService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class Yield extends JavaPlugin {

    private static final long AUTOSAVE_INTERVAL_TICKS = 20L * 60 * 2; // 2 minutes

    private ListenerManager listenerManager;
    private DatabaseManager databaseManager;
    private ScoreboardManager scoreboardManager;
    private PlaceholderRegistry placeholderRegistry;
    private PlayerProfileManager playerProfileManager;
    private GuiManager guiManager;
    private SchematicService schematicService;

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

        YieldScoreboardDisplay scoreboardDisplay = new YieldScoreboardDisplay(this, scoreboardManager);
        listenerManager.register(scoreboardDisplay);
        scoreboardDisplay.start();

        listenerManager.register(new ChatFormatter());

        guiManager = new GuiManager();
        listenerManager.register(new GuiListener(guiManager));

        CommandManager.register(this, YieldCommand.build(this), "Yield admin commands", List.of("yld"));
        CommandManager.register(this, AdminCommands.gamemode("gmc", GameMode.CREATIVE), "Set gamemode to creative");
        CommandManager.register(this, AdminCommands.gamemode("gms", GameMode.SURVIVAL), "Set gamemode to survival");
        CommandManager.register(this, AdminCommands.gamemode("gmsp", GameMode.SPECTATOR), "Set gamemode to spectator");
        CommandManager.register(this, AdminCommands.gamemode("gma", GameMode.ADVENTURE), "Set gamemode to adventure");
        CommandManager.register(this, AdminCommands.fly(), "Toggle flight");
    }

    @Override
    public void onDisable() {
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

    /** Null if FastAsyncWorldEdit isn't installed - always check before using. */
    public SchematicService getSchematicService() {
        return schematicService;
    }
}
