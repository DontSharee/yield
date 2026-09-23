package me.dontshare.yieldtools.data;

import org.bukkit.Material;

import java.util.List;

/** One tool on the path - see tools.yml. {@code index} is its position on the path, 0 for the first. */
public record ToolDefinition(int index, String id, String displayName, Material material, double tapMultiplier,
                             long costCoins, List<String> description) {
}
