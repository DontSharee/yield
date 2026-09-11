package me.dontshare.yieldmining.event;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired from {@link me.dontshare.yieldmining.MiningService#onBreak} once a
 * reward-eligible real ore block has been broken and its coin payout
 * applied. Lets other plugins (e.g. yield-leveling) react without
 * yield-mining needing any awareness of them - mirrors yield-zones' own
 * OreCubeKilledEvent.
 */
public final class OreMinedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Material material;
    private final long coinsEarned;
    private final int xp;

    public OreMinedEvent(Player player, Material material, long coinsEarned, int xp) {
        this.player = player;
        this.material = material;
        this.coinsEarned = coinsEarned;
        this.xp = xp;
    }

    public Player getPlayer() {
        return player;
    }

    /** The normalized base material (deepslate variants already stripped) - see MiningService#normalize. */
    public Material getMaterial() {
        return material;
    }

    public long getCoinsEarned() {
        return coinsEarned;
    }

    public int getXp() {
        return xp;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
