package me.dontshare.yieldachievements.potion;

/**
 * A potion is entirely defined by its own id string - "POTION_COINS_2_300"
 * means a 2x Coins potion lasting 300 seconds. No separate registry/config
 * needed: any admin command, milestone reward, or achievement reward can
 * reference a brand-new potion just by writing its id, the same "self-
 * documenting identifier" idea as achievements' "PRESTIGE_100" shorthand.
 */
public record PotionDefinition(String id, PotionStat stat, double multiplier, long durationSeconds) {

    /** Null if {@code id} isn't a valid "POTION_<STAT>_<MULTIPLIER>_<SECONDS>" string. */
    public static PotionDefinition parse(String id) {
        if (id == null) {
            return null;
        }
        String[] parts = id.split("_");
        if (parts.length != 4 || !parts[0].equals("POTION")) {
            return null;
        }
        try {
            PotionStat stat = PotionStat.valueOf(parts[1]);
            double multiplier = Double.parseDouble(parts[2]);
            long durationSeconds = Long.parseLong(parts[3]);
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
