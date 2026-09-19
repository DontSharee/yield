package me.dontshare.yieldpacks.mastery;

/**
 * Which real, already-existing provider registry a {@link MasteryPerk}'s
 * value feeds - one entry per hook this system actually plugs into (see
 * {@code YieldPacks}/yield-zones' {@code OreCubeService}/
 * {@code PetCombatController} for the registries themselves). A perk's own
 * {@code value} is always a plain additive number regardless of which
 * kind of stat this is - for a naturally multiplicative one (coin/damage/
 * diamond/attack-speed/open-speed), the CONSUMER wraps the summed total as
 * {@code 1.0 + sum} once, not the perk itself.
 */
public enum MasteryStat {
    COIN_MULTIPLIER,
    DAMAGE_MULTIPLIER,
    ATTACK_SPEED_MULTIPLIER,
    DIAMOND_MULTIPLIER,
    LUCK,
    EXTRA_PET_SLOTS,
    OPEN_SPEED_MULTIPLIER,
    EXTRA_CUBE_CAP,
    DIAMOND_CHANCE_BOOST,
    CUBE_BONUS_CHANCE_BOOST,
    CRIT_CHANCE,
    DOUBLE_HIT_CHANCE,
    TRIPLE_HIT_CHANCE,
    ENCHANT_BONUS_SLOTS
}
