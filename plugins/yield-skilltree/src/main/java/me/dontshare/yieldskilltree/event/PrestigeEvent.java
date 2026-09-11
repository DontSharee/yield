package me.dontshare.yieldskilltree.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired from {@code PrestigeService#prestige} on a successful prestige. */
public final class PrestigeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int totalPrestiges;

    public PrestigeEvent(Player player, int totalPrestiges) {
        this.player = player;
        this.totalPrestiges = totalPrestiges;
    }

    public Player getPlayer() {
        return player;
    }

    public int getTotalPrestiges() {
        return totalPrestiges;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
