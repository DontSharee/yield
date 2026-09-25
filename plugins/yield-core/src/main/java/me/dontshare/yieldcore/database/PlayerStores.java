package me.dontshare.yieldcore.database;

import me.dontshare.yieldcore.listener.ListenerManager;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Creates a {@link PlayerDataStore} with its whole lifecycle already wired:
 * loaded before the player is let in, saved and unloaded when they leave,
 * and autosaved in between.
 * <p>
 * That sequence is not optional detail - a store whose login load is missing
 * hands out default data instead, and the first save then writes that over
 * whatever the player really had. Since every feature owning its own slice
 * of player data means every feature repeating this, it is written once here
 * rather than copied per plugin, where one omission is silent and looks
 * exactly like a player losing their progress.
 */
public final class PlayerStores {

    private static final long DEFAULT_AUTOSAVE_INTERVAL_TICKS = 20L * 60 * 2; // 2 minutes

    private PlayerStores() {
    }

    /**
     * @param owner      the plugin whose data this is - owns the autosave task
     * @param fieldKey   this plugin's own top-level field in the shared player document, e.g. "quests"
     * @param dataLabel  what to call this data in a failure log, e.g. "quest data"
     */
    public static <T extends PlayerRecord> PlayerDataStore<T> register(
            JavaPlugin owner, ListenerManager listenerManager, DatabaseManager databaseManager,
            String fieldKey, Class<T> type, Function<UUID, T> defaultFactory, String dataLabel) {

        PlayerDataStore<T> store = new PlayerDataStore<>(
                databaseManager, "playerData", fieldKey, type, defaultFactory, owner.getLogger());
        listenerManager.register(new Lifecycle<>(store, owner, dataLabel));
        store.startAutoSave(owner, DEFAULT_AUTOSAVE_INTERVAL_TICKS);
        return store;
    }

    private record Lifecycle<T extends PlayerRecord>(PlayerDataStore<T> store, JavaPlugin owner, String dataLabel)
            implements Listener {

        @EventHandler(priority = EventPriority.LOWEST)
        public void onPreLogin(AsyncPlayerPreLoginEvent event) {
            UUID playerId = event.getUniqueId();
            try {
                store.loadBlocking(playerId);
            } catch (Exception e) {
                // Never let them in on default data - the next save would
                // write it over what they actually had.
                owner.getLogger().log(Level.SEVERE, "Failed to load " + dataLabel + " for " + playerId + "; denying login.", e);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Failed to load your data. Please try rejoining in a moment."));
            }
        }

        /**
         * The owning plugin is shutting down: save everyone it holds, now.
         * On a server stop, plugins are disabled BEFORE players are
         * disconnected, so {@link #onQuit} never runs then - without this,
         * every restart dropped whatever each player had changed in this
         * store since their last autosave (up to two minutes). This listener
         * belongs to yield-core, which every store's plugin depends on, so
         * it's still registered - and the database still open - when the
         * owner goes.
         */
        @EventHandler
        public void onOwnerDisable(org.bukkit.event.server.PluginDisableEvent event) {
            if (event.getPlugin() == owner) {
                store.saveAllSync();
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPreLoginResult(AsyncPlayerPreLoginEvent event) {
            if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                store.abandonLogin(event.getUniqueId());
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            store.markJoined(event.getPlayer().getUniqueId());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            UUID playerId = event.getPlayer().getUniqueId();
            long session = store.takeJoinedSession(playerId);
            store.saveNow(playerId).whenComplete((ignored, error) -> {
                if (error == null) {
                    store.unload(playerId, session);
                }
                // On failure it stays cached so the next autosave retries -
                // unloading would discard the only copy of whatever didn't land.
            });
        }
    }
}
