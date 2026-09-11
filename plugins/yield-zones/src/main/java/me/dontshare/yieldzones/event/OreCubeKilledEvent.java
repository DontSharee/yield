package me.dontshare.yieldzones.event;

import me.dontshare.yieldzones.data.CubeTier;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired from {@link me.dontshare.yieldzones.cube.OreCubeService#payOut} once a
 * cube's kill payout has actually been applied - {@code coinsEarned}/
 * {@code gemsEarned} are the real, multiplier-adjusted amounts credited. Lets
 * other plugins (e.g. yield-quests) react without yield-zones needing any
 * awareness of them.
 */
public final class OreCubeKilledEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final CubeTier tier;
    private final long coinsEarned;
    private final long gemsEarned;

    public OreCubeKilledEvent(Player player, CubeTier tier, long coinsEarned, long gemsEarned) {
        this.player = player;
        this.tier = tier;
        this.coinsEarned = coinsEarned;
        this.gemsEarned = gemsEarned;
    }

    public Player getPlayer() {
        return player;
    }

    public CubeTier getTier() {
        return tier;
    }

    public long getCoinsEarned() {
        return coinsEarned;
    }

    public long getGemsEarned() {
        return gemsEarned;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
