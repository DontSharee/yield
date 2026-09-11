package me.dontshare.yieldlootboxes.data;

import org.bukkit.Material;

import java.util.List;

public record LootboxDefinition(String id, String displayName, Material icon, List<LootboxRewardEntry> pool) {
}
