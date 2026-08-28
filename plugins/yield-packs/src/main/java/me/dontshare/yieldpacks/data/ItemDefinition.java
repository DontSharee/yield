package me.dontshare.yieldpacks.data;

import org.bukkit.Material;

import java.util.List;

/**
 * A pet definition, entirely config-driven (see packs.yml). If
 * {@code headDatabaseId} is set (and the HeadDatabase plugin is installed),
 * the pet renders as that HeadDatabase player head instead of
 * {@code material} - see {@code me.dontshare.yieldpacks.item.ItemIconFactory}.
 */
public record ItemDefinition(String id, String displayName, Material material, Integer customModelData,
                              String headDatabaseId, String rarityId, double valuePerSecond,
                              boolean trackExists, List<String> lore) {
}
