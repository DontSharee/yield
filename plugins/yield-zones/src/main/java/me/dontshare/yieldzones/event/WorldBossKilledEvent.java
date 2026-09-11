package me.dontshare.yieldzones.event;

import me.dontshare.yieldzones.boss.WorldBossDefinition;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Map;
import java.util.UUID;

/**
 * Fired from {@link me.dontshare.yieldzones.boss.WorldBossService} once a
 * world boss's HP hits 0 and payouts have been applied - {@code
 * damageByPlayer} is every contributor's total damage dealt (used to split
 * {@code rewardCoins}/{@code rewardGems} proportionally), not just the
 * killing blow. Unlike {@link OreCubeKilledEvent}, this has no single
 * "player" - a world boss is a shared, multiplayer kill.
 */
public final class WorldBossKilledEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final WorldBossDefinition definition;
    private final Map<UUID, Long> damageByPlayer;
    private final long rewardCoins;
    private final long rewardGems;

    public WorldBossKilledEvent(WorldBossDefinition definition, Map<UUID, Long> damageByPlayer, long rewardCoins, long rewardGems) {
        this.definition = definition;
        this.damageByPlayer = damageByPlayer;
        this.rewardCoins = rewardCoins;
        this.rewardGems = rewardGems;
    }

    public WorldBossDefinition getDefinition() {
        return definition;
    }

    public Map<UUID, Long> getDamageByPlayer() {
        return damageByPlayer;
    }

    public long getRewardCoins() {
        return rewardCoins;
    }

    public long getRewardGems() {
        return rewardGems;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
