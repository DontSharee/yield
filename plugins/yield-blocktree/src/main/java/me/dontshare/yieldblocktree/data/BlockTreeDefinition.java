package me.dontshare.yieldblocktree.data;

import org.bukkit.Material;

import java.util.List;

/**
 * One block's whole tree - keyed by {@code material} everywhere (progress/claim keys, provider lookups), not by its blocktree.yml section id, which exists only for readability in the config.
 * {@code perkTitle} is the name of the tree's top reward as menus show it ("&6&lMidas Touch") - taken from its
 * {@code perk} effect, or from the block's own {@code perk-name} when the top tier is a plain stat instead. Null when neither says.
 */
public record BlockTreeDefinition(Material material, String displayName, Material icon, List<BlockTreeTier> tiers, String perkTitle) {
}
