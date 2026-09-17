package me.dontshare.yieldpacks.mastery;

import java.util.List;

/**
 * One discrete, named, level-gated unlock within a mastery track - PS99-
 * style ("Damage Boost II", unlocked at level 40), as opposed to this
 * system's old shape (one smooth bonus that just grew every level). Every
 * perk whose {@code level} is at or below the player's current level in
 * that track is permanently active - {@code value} is that perk's own
 * standalone contribution to {@code stat}; when two perks share a
 * {@code stat} (e.g. "Damage Boost I" and "Damage Boost II"), both stay
 * active once unlocked and their values simply sum (see
 * {@link MasteryService#sumStat}) - there's no "higher tier replaces
 * lower tier" logic anywhere in this system.
 */
public record MasteryPerk(String id, String displayName, int level, MasteryStat stat, double value, List<String> description) {
}
