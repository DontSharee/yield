package me.dontshare.yieldpacks.leveling;

/** A per-pet reward unlocked once that specific instance reaches a configured level - see PetLevelingService. */
public enum MilestoneEffect {
    /**
     * Each equipped pet at this level adds its value to the player's tap
     * damage multiplier - see PetLevelingService#tapBonusFor. Replaced
     * AUTO_ATTACK ("this pet fights without being sent") when every pet
     * started fighting on its own and that reward stopped meaning anything.
     */
    TAP_DAMAGE_BONUS,
    /** Boosts the coin payout of any kill this pet contributes to - see PetLevelingService#earningsBonusFor. */
    EARNINGS_BONUS,
    /** Forces a diamond drop (instead of the normal luck-rolled chance) on any kill this pet contributes to. */
    GUARANTEED_DIAMOND_DROP
}
