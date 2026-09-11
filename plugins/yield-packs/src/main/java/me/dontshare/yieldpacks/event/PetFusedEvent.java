package me.dontshare.yieldpacks.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired from {@link me.dontshare.yieldpacks.gui.FusionGui#attemptFuse} right
 * after a successful fuse - lets other plugins (e.g. yield-quests) react
 * without yield-packs needing any awareness of them.
 */
public final class PetFusedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String resultId;

    public PetFusedEvent(Player player, String resultId) {
        this.player = player;
        this.resultId = resultId;
    }

    public Player getPlayer() {
        return player;
    }

    public String getResultId() {
        return resultId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
