package me.dontshare.yieldpacks.event;

import me.dontshare.yieldpacks.shard.ShardType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired the instant a Shard drop is actually GIVEN to a player (see
 * yield-blocktree's {@code BlockTreeProgressListener}) - not on consume.
 * Lets other plugins (e.g. yield-broadcasts, for a Perfect find) react
 * without yield-packs needing any awareness of them.
 */
public final class ShardFoundEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ShardType type;
    private final boolean perfect;

    public ShardFoundEvent(Player player, ShardType type, boolean perfect) {
        this.player = player;
        this.type = type;
        this.perfect = perfect;
    }

    public Player getPlayer() {
        return player;
    }

    public ShardType getType() {
        return type;
    }

    public boolean isPerfect() {
        return perfect;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
