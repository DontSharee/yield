package me.dontshare.yieldupgrades.data;

/**
 * What an upgrade type actually plugs into - see {@code UpgradeService}'s
 * per-effect query methods and {@code YieldUpgrades#onEnable} for where each
 * one registers into another plugin's own composable provider registry.
 * GEM_BOOST is the one effect that drives two separate registries at once
 * (chance AND flat amount) rather than one.
 */
public enum UpgradeEffect {
    COIN_MULTIPLIER,
    GEM_BOOST,
    DAMAGE_MULTIPLIER,
    REBIRTH_GRANT_MULTIPLIER,
    PLAYER_SPEED,
    /** Extra concurrent-cube slots on top of a zone's own configured base (whole number of slots per level). */
    CUBE_CAP_BONUS,
    /** Additive boost to every configured cube-bonus's own chance (golden/diamond) - see OreCubeService#rollBonus. */
    CUBE_BONUS_CHANCE,
    /** Speeds up how quickly Auto Mode's pets re-engage after their shared target dies and a new one is auto-picked - see PetCombatController's own switch-cooldown, and UpgradeService#autoSwitchSpeedMultiplier. */
    AUTO_SWITCH_SPEED
}
