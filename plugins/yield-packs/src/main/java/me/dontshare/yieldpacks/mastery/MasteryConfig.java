package me.dontshare.yieldpacks.mastery;

import java.util.List;
import java.util.Map;

/** Parsed masteries.yml - see MasteryContentLoader. */
public record MasteryConfig(double curveBase, double curveExponent, int maxLevel, Map<MasteryType, List<MasteryPerk>> perksByTrack) {

    /** Every perk for {@code type}, or an empty list if none are configured. */
    public List<MasteryPerk> perksFor(MasteryType type) {
        return perksByTrack.getOrDefault(type, List.of());
    }
}
