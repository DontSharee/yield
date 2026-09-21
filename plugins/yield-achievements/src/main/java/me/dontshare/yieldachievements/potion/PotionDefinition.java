package me.dontshare.yieldachievements.potion;

/**
 * A potion is entirely defined by its own id string - "POTION_COINS_2_300"
 * means a 2x Coins potion lasting 300 seconds. No separate registry/config
 * needed: any admin command, milestone reward, or achievement reward can
 * reference a brand-new potion just by writing its id, the same "self-
 * documenting identifier" idea as achievements' "PRESTIGE_100" shorthand.
 */
public record PotionDefinition(String id, PotionStat stat, double multiplier, long durationSeconds) {

    private static final String PREFIX = "POTION_";

    /**
     * Null if {@code id} isn't a valid "POTION_&lt;STAT&gt;_&lt;MULTIPLIER&gt;_&lt;SECONDS&gt;" string.
     * <p>
     * The stat is taken as everything between the prefix and the LAST two
     * fields rather than by splitting on every underscore, because a stat
     * name may contain one: {@link PotionStat#ROLL_SPEED} made
     * "POTION_ROLL_SPEED_2_600" five fields where a four-field split
     * expected four, so every Roll Speed potion ever written - by an admin,
     * a milestone reward or a store product - parsed to null and silently
     * did nothing. The admin command's own error message
     * ("expected COINS, DAMAGE, LUCK, or ROLL_SPEED") was offering a stat
     * this method could not accept.
     */
    public static PotionDefinition parse(String id) {
        if (id == null || !id.startsWith(PREFIX)) {
            return null;
        }
        int durationAt = id.lastIndexOf('_');
        int multiplierAt = durationAt <= 0 ? -1 : id.lastIndexOf('_', durationAt - 1);
        // Must sit at or past the prefix, or the stat name would be empty -
        // and a substring(7, 6) is an IndexOutOfBounds, not the
        // IllegalArgumentException the catch below is for.
        if (multiplierAt < PREFIX.length()) {
            return null;
        }
        String statName = id.substring(PREFIX.length(), multiplierAt);
        try {
            PotionStat stat = PotionStat.valueOf(statName);
            double multiplier = Double.parseDouble(id.substring(multiplierAt + 1, durationAt));
            long durationSeconds = Long.parseLong(id.substring(durationAt + 1));
            if (multiplier <= 0 || durationSeconds <= 0) {
                return null;
            }
            return new PotionDefinition(id, stat, multiplier, durationSeconds);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The compound key two potions must share to stack duration instead of running as separate concurrent effects - see PotionService. */
    public String stackKey() {
        return stat.name() + "_" + multiplier;
    }
}
