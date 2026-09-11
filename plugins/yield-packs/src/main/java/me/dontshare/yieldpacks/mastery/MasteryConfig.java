package me.dontshare.yieldpacks.mastery;

/** Parsed masteries.yml - see MasteryContentLoader. */
public record MasteryConfig(double curveBase, double curveExponent, int maxLevel, double bonusPerLevel) {
}
