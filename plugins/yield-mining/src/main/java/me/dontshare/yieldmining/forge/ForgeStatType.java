package me.dontshare.yieldmining.forge;

/** Which permanent player-wide stat a forged item's Multi boosts once fed to a pet - see ForgeBoostService. Crit chance is deliberately excluded (that provider lives on yield-zones' PetCombatController; not worth a new cross-plugin dependency for one of six stats). */
public enum ForgeStatType {
    DAMAGE, COINS, GEMS, LUCK, ATTACK_SPEED
}
