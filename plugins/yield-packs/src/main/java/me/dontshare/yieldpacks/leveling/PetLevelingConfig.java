package me.dontshare.yieldpacks.leveling;

import java.util.List;

/** Parsed pet-leveling.yml - see PetLevelingContentLoader. */
public record PetLevelingConfig(double curveBase, double curveExponent, double damagePerLevel,
                                 int maxLevel, int defaultLevelCap, List<PetMilestone> milestones) {
}
