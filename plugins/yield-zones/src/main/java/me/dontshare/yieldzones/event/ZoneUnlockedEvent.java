package me.dontshare.yieldzones.event;

import me.dontshare.yieldzones.data.ZoneDefinition;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired from {@code ZoneLockService#attemptPurchase} on a successful purchase. */
public final class ZoneUnlockedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ZoneDefinition zone;

    public ZoneUnlockedEvent(Player player, ZoneDefinition zone) {
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
