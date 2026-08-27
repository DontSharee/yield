package me.dontshare.yield.gui;

import org.bukkit.entity.Player;

import java.util.Map;
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

    void forget(UUID playerId) {
        current.remove(playerId);
    }
}
