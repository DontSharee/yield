package me.dontshare.yieldpacks.event;

import me.dontshare.yieldpacks.leveling.Candy;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired from {@link me.dontshare.yieldpacks.leveling.PetLevelingService#feedCandy} on a successful feed. */
public final class PetCandyFedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID instanceId;
    private final Candy candy;

    public PetCandyFedEvent(Player player, UUID instanceId, Candy candy) {
        this.player = player;
        this.instanceId = instanceId;
        this.candy = candy;
    }

    public Player getPlayer() {
        return player;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public Candy getCandy() {
        return candy;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
