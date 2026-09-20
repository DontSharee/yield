package me.dontshare.yieldachievements.boost;

import me.dontshare.yieldachievements.potion.PotionStat;

/**
 * One live server-wide boost: everyone online gets {@code multiplier} on
 * {@code stat} until {@code endsAtMillis}.
 * <p>
 * Deliberately the same {@link PotionStat} set personal potions use, and it
 * feeds the same multiplier registries in {@code YieldPacks} - a server
 * boost is simply a potion that everybody is drinking at once, so there is
 * no second notion of "what can be boosted" to keep in sync.
 * <p>
 * {@code startedBySeconds} is the duration the boost was STARTED with, not
 * what is left of it - the boss bar needs it to draw a bar that drains from
 * full rather than jumping to an arbitrary fraction.
 */
public record ServerBoost(PotionStat stat, double multiplier, long endsAtMillis, long startedForSeconds) {

    /** The key two boosts must share to extend one another rather than run side by side - same rule potions use. */
    public String key() {
        return stat.name() + "_" + multiplier;
    }

    public long remainingSeconds() {
        return Math.max(0, (endsAtMillis - System.currentTimeMillis() + 999) / 1000);
    }

    public boolean expired() {
        return System.currentTimeMillis() >= endsAtMillis;
    }

    /** 0.0 once spent, 1.0 at the moment it started - what the boss bar draws. */
    public float progress() {
        if (startedForSeconds <= 0) {
            return 0f;
        }
        float fraction = (float) remainingSeconds() / (float) startedForSeconds;
        return Math.max(0f, Math.min(1f, fraction));
    }

    /** "2x", "1.5x" - trailing ".0" trimmed so the common case reads as a whole number. */
    public String multiplierLabel() {
        return multiplier == Math.rint(multiplier)
                ? String.valueOf((long) multiplier) + "x"
                : multiplier + "x";
    }
}
