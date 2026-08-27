package me.dontshare.yield.player;

import me.dontshare.yield.database.PlayerDataStore;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reference implementation of the {@link PlayerDataStore} join/quit
 * pattern - copy this shape for any other plugin's player data, each
 * against its own store/collection.
 */
public final class PlayerProfileManager implements Listener {

    private final PlayerDataStore<PlayerProfile> store;
    private final Logger logger;

    public PlayerProfileManager(PlayerDataStore<PlayerProfile> store, Logger logger) {
        this.store = store;
        this.logger = logger;
    }

    public PlayerDataStore<PlayerProfile> getStore() {
        return store;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID playerId = event.getUniqueId();
        try {
            PlayerProfile profile = store.loadBlocking(playerId);
            profile.setUsername(event.getName());
            profile.setLastSeen(System.currentTimeMillis());
        } catch (Exception e) {
            // Never let the player join on default data - that default
            // data would then get saved over whatever they actually had.
            logger.log(Level.SEVERE, "Failed to load player data for " + playerId + "; denying login.", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Failed to load your data. Please try rejoining in a moment."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        store.save(playerId).whenComplete((ignored, error) -> {
            if (error == null) {
                store.unload(playerId);
            }
            // On failure, leave it cached so the next autosave cycle
            // retries - unloading here would discard the only remaining
            // copy of whatever didn't make it to Mongo.
        });
    }
}
