package me.dontshare.yieldleveling.data;

/** Parsed player-leveling.yml - see PlayerLevelingContentLoader. */
public record PlayerLevelingConfig(double curveBase, double curveExponent, int maxLevel) {
}
