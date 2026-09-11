package me.dontshare.yieldpacks.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired from {@link me.dontshare.yieldpacks.gui.BagGui#unequip} right after a successful manual unequip - mirrors {@link PetEquippedEvent}. */
public final class PetUnequippedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID instanceId;

    public PetUnequippedEvent(Player player, UUID instanceId) {
        this.player = player;
        this.instanceId = instanceId;
    }

    public Player getPlayer() {
        return player;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
