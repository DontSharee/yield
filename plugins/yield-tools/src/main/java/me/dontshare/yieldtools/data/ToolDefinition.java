package me.dontshare.yieldtools.data;

import org.bukkit.Material;

/**
 * One weapon on the path - see tools.yml. {@code index} is its position on
 * the path, 0 for the first; {@code power} is what a tap deals while it's
 * held, as a multiple of pet power. No description: every weapon shows the
 * same generated stats, so the path reads as one consistent list.
 */
public record ToolDefinition(int index, String id, String displayName, Material material, double power,
                             long costCoins) {
}
