package me.dontshare.yieldpacks.leveling;

/** A per-pet reward unlocked once that specific instance reaches a configured level - see PetLevelingService. */
public enum MilestoneEffect {
    /** This specific pet fights on its own regardless of the player's SendMode, while non-milestone squadmates still wait to be sent. */
    AUTO_ATTACK,
    /** Boosts the coin payout of any kill this pet contributes to - see PetLevelingService#earningsBonusFor. */
    EARNINGS_BONUS,
    /** Forces a diamond drop (instead of the normal luck-rolled chance) on any kill this pet contributes to. */
    GUARANTEED_DIAMOND_DROP
}
