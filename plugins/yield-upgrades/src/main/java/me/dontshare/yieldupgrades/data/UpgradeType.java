package me.dontshare.yieldupgrades.data;

import org.bukkit.Material;

/**
 * One upgrade's static config - its global per-player level (see
 * {@code PackPlayerProfile#getUpgradeLevels}) is keyed by {@code id} and
 * shared across every physical station of this type, regardless of zone.
 * {@code chancePerLevel}/{@code flatPerLevel} are only meaningful for
 * {@link UpgradeEffect#DIAMOND_BOOST} (0 otherwise); every other effect uses
 * {@code valuePerLevel} alone.
 * <p>
 * Cost uses an exponential per-level curve - {@code costBase * costGrowth^level} -
 * deliberately different from the power-curve (base * level^exponent) used
 * for player/pet leveling's rarer, bigger milestone jumps: this is a "many
 * small frequent purchases" idle-game curve instead.
 */
public record UpgradeType(String id, String displayName, Material icon, UpgradeEffect effect,
                           double valuePerLevel, double chancePerLevel, double flatPerLevel,
                           int maxLevel, double costBase, double costGrowth) {
}
