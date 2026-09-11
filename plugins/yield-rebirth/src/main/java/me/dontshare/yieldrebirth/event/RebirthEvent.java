package me.dontshare.yieldrebirth.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired from {@code RebirthService#rebirth} whenever at least one rebirth was actually applied. */
public final class RebirthEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int rebirthsGained;
    private final int totalRebirths;

    public RebirthEvent(Player player, int rebirthsGained, int totalRebirths) {
        this.player = player;
        this.rebirthsGained = rebirthsGained;
        this.totalRebirths = totalRebirths;
    }

    public Player getPlayer() {
        return player;
    }

    /** How many rebirths this specific call applied - a preview can roll more than one at once if affordable. */
    public int getRebirthsGained() {
        return rebirthsGained;
    }

    public int getTotalRebirths() {
        return totalRebirths;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
