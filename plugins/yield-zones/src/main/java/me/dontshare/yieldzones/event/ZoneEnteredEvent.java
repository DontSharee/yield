package me.dontshare.yieldzones.event;

import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired the instant a player's tracked zone changes to a non-null value - see {@code OreCubeService#tick}. Any number of plugins can listen (yield-tutorial's "walk into a zone" step, yield-quests' ENTER_ZONE trigger, etc). */
public final class ZoneEnteredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ZoneDefinition zone;

    public ZoneEnteredEvent(Player player, ZoneDefinition zone) {
        this.player = player;
        this.zone = zone;
    }

    public Player getPlayer() {
        return player;
    }

    public ZoneDefinition getZone() {
        return zone;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
