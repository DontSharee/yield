package me.dontshare.yieldmining.data;

import org.bukkit.Material;

/**
 * One minable ore type - keyed by its BASE material (e.g. {@code COAL_ORE});
 * a deepslate variant break normalizes to this same base before lookup (see
 * {@code MiningService#normalize}), so both stone-tier and deepslate-tier
 * ore share one definition and one blocktree progress track.
 * <p>
 * {@code dropMaterial} is the real, physical item handed to the player on a
 * successful mine (e.g. {@code RAW_IRON} for {@code IRON_ORE}) - null skips
 * giving an item entirely. {@code coinReward}/{@code xp} are on top of that,
 * not instead of it.
 */
public record OreDefinition(Material material, long coinReward, int xp, int regenTicks,
                             Material dropMaterial, int dropMin, int dropMax) {
}
