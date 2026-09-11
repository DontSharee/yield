package me.dontshare.yieldachievements.data;

import org.bukkit.Material;

import java.util.List;

/**
 * A configurable category (e.g. "Cubes Killed") - every tier in {@code
 * tiers} tracks the SAME underlying counter, incremented whenever {@code
 * trigger} fires. Fully data-driven (see milestones.yml) so adding a new
 * category is a config change, never a code change.
 */
public record MilestoneCategory(String id, String displayName, Material icon, GameAction trigger, List<MilestoneTier> tiers) {
}
