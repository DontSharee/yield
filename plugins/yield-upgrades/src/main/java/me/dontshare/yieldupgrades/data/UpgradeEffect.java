package me.dontshare.yieldupgrades.data;

/**
 * What an upgrade type actually plugs into - see {@code UpgradeService}'s
 * per-effect query methods and {@code YieldUpgrades#onEnable} for where each
 * one registers into another plugin's own composable provider registry.
 * DIAMOND_BOOST is the one effect that drives two separate registries at once
 * (chance AND flat amount) rather than one.
 */
public enum UpgradeEffect {
    COIN_MULTIPLIER,
    DIAMOND_BOOST,
    DAMAGE_MULTIPLIER,
    REBIRTH_GRANT_MULTIPLIER,
    PLAYER_SPEED,
    /** Extra concurrent-cube slots on top of a zone's own configured base (whole number of slots per level). */
    CUBE_CAP_BONUS,
    /** Additive boost to every configured cube-bonus's own chance (golden/diamond) - see OreCubeService#rollBonus. */
    CUBE_BONUS_CHANCE,
    /** Speeds up how quickly Auto Mode's pets re-engage after their shared target dies and a new one is auto-picked - see PetCombatController's own switch-cooldown, and UpgradeService#autoSwitchSpeedMultiplier. */
    AUTO_SWITCH_SPEED,
    /** Factor on every tap's damage - see TapService#tapDamageAt. */
    TAP_DAMAGE,
    /** Additive crit chance on every pet hit, on top of the base 10% - see PetCombatController#applyDamage. */
    CRIT_CHANCE,
    /** Factor on every diamond payout - the same slot ranks and potions use. */
    DIAMOND_MULTIPLIER,
    /** Factor on how often pets attack. */
    ATTACK_SPEED,
    /** Additive hatch luck, the same slot potions and completed indexes feed. */
    HATCH_LUCK,
    /** Cubes respawn faster: the delay is divided by {@code 1 + level * value}. */
    RESPAWN_SPEED,
    /** Additive chance for a pet hit to land a second time. */
    DOUBLE_HIT
}
