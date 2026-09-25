package me.dontshare.yieldpacks.player;

import me.dontshare.yieldcore.database.PlayerDataStore;
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
 * Join/quit/autosave lifecycle for {@link PackPlayerProfile} - copies
 * yield-core's PlayerProfileManager shape exactly.
 */
public final class PackPlayerProfileManager implements Listener {

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Logger logger;

    public PackPlayerProfileManager(PlayerDataStore<PackPlayerProfile> store, Logger logger) {
        this.store = store;
        this.logger = logger;
    }

    public PlayerDataStore<PackPlayerProfile> getStore() {
        return store;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID playerId = event.getUniqueId();
        try {
            store.loadBlocking(playerId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load pack data for " + playerId + "; denying login.", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Failed to load your data. Please try rejoining in a moment."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        store.saveNow(playerId).whenComplete((ignored, error) -> {
            if (error == null) {
                store.unload(playerId);
            }
        });
    }
}
