package me.dontshare.yieldcore.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks each player's currently-open {@link Gui} and the one before it, for
 * {@link #openLast} ("go back") navigation - click routing itself doesn't
 * need this, {@link GuiListener} recovers the Gui straight from the
 * inventory's holder.
 */
public final class GuiManager {

    private final Map<UUID, Gui> current = new ConcurrentHashMap<>();
    private final Map<UUID, Gui> previous = new ConcurrentHashMap<>();

    public void open(Player player, Gui gui) {
        Gui was = current.get(player.getUniqueId());
        if (was != null && was != gui) {
            previous.put(player.getUniqueId(), was);
        }
        current.put(player.getUniqueId(), gui);
        player.openInventory(gui.getInventory());
    }

    /** Reopens whatever GUI this player was viewing before their current one, if any. */
    public void openLast(Player player) {
        Gui last = previous.get(player.getUniqueId());
        if (last != null) {
            open(player, last);
        }
    }

    /**
     * {@code closed} has just closed. Only forgotten if it is still the
     * current screen: opening one menu from another fires the old one's
     * close after the new one is already current, and dropping that would
     * leave {@link #closeAll} blind to a menu that may hold real items.
     */
    void forget(UUID playerId, Gui closed) {
        current.remove(playerId, closed);
    }

    /** The player left - nothing of theirs should outlive them here. */
    void forgetPlayer(UUID playerId) {
        current.remove(playerId);
        previous.remove(playerId);
    }

    /**
     * Closes every open screen, running each one's close handler.
     * <p>
     * Call this from {@code onDisable}. Plugins are disabled before players
     * are kicked and saved, so the {@code InventoryCloseEvent} a shutdown
     * eventually produces arrives with no listener left to hear it - which
     * means a screen holding real player items in editable slots (the Forge
     * grid, a candy being applied) would simply destroy them on every
     * restart and every {@code /reload}.
     */
    public void closeAll() {
        for (UUID playerId : Set.copyOf(current.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.closeInventory();
            }
        }
        current.clear();
        previous.clear();
    }
}
