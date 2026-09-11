package me.dontshare.yieldteams.data;

/** What a team upgrade actually boosts - each is consumed as {@code 1.0 + TeamService#totalBonus(...)}, composing with every other multiplier source the same way rebirth/prestige/skilltree bonuses already do. */
public enum TeamUpgradeType {
    COIN_MULTIPLIER,
    LUCK_MULTIPLIER,
    DAMAGE_MULTIPLIER
}
