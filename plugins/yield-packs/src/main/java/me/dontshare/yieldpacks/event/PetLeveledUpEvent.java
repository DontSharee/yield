package me.dontshare.yieldpacks.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired from {@link me.dontshare.yieldpacks.leveling.PetLevelingService#grantKillXp}
 * once per pet whose level actually increased that call (a single big XP
 * grant can roll more than one level - this fires once per level gained,
 * not once per call, so a quest tracking "reach level 25" style milestones
 * sees every level crossed, not just the final one).
 */
public final class PetLeveledUpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final UUID instanceId;
    private final int newLevel;

    public PetLeveledUpEvent(Player player, UUID instanceId, int newLevel) {
        this.player = player;
        this.instanceId = instanceId;
        this.newLevel = newLevel;
    }

    public Player getPlayer() {
        return player;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public int getNewLevel() {
        return newLevel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
