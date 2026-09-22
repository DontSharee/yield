package me.dontshare.yieldevents.data;

import org.bukkit.Material;

import java.util.List;

/**
 * One thing an event's currency buys that is not an egg.
 * <p>
 * The egg is a slot machine; this is the shelf next to it. A player who
 * has ground out a fortnight of a seasonal currency should be able to
 * decide what it turns into rather than only feeding it back into odds,
 * and the two want opposite things from a price: eggs are bought hundreds
 * of times and so are cheap, while a shelf item is bought once and so is
 * expensive enough to be a decision.
 * <p>
 * {@code limit} is what makes it a shop rather than a second egg. A stock
 * of 1 is the pity buy - the guaranteed version of a pet the odds may
 * never give you - and a stock of 3 is exactly enough to fuse. Zero means
 * unlimited, for consumables where repeat buying is the point.
 * <p>
 * Rewards are {@code commands} for the same reason quests' are (see
 * {@link EventQuest}): every reward type already has an admin command, and
 * a seasonal shelf changes every year and should not need code to.
 */
public record EventShopEntry(String id, String displayName, List<String> description, Material material,
                              long price, int limit, List<String> commands) {

    /** Whether this entry restocks forever. */
    public boolean unlimited() {
        return limit <= 0;
    }

    /** How many of this a player may still buy, given how many they already have. */
    public int remaining(int bought) {
        return unlimited() ? Integer.MAX_VALUE : Math.max(0, limit - bought);
    }
}
