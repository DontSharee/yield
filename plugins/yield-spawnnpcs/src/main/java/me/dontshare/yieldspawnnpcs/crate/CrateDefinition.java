package me.dontshare.yieldspawnnpcs.crate;

import org.bukkit.Material;

import java.util.List;

public record CrateDefinition(String id, String displayName, Material icon, long cost, List<CrateRewardEntry> pool) {
}
