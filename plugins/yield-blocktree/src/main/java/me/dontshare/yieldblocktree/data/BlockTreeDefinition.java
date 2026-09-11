package me.dontshare.yieldblocktree.data;

import org.bukkit.Material;

import java.util.List;

/** One block's whole tree - keyed by {@code material} everywhere (progress/claim keys, provider lookups), not by its blocktree.yml section id, which exists only for readability in the config. */
public record BlockTreeDefinition(Material material, String displayName, Material icon, List<BlockTreeTier> tiers) {
}
