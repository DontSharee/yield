package me.dontshare.yieldpacks.data;

import org.bukkit.Material;

import java.util.List;

/** A pack definition, entirely config-driven (see packs.yml). */
public record PackDefinition(String id, String displayName, Material material, Integer customModelData,
                              long coinCost, long gemCost, int sortOrder, List<PackPoolEntry> pool) {
}
