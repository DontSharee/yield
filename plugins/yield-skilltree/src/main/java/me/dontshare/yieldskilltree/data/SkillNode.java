package me.dontshare.yieldskilltree.data;

import org.bukkit.Material;

import java.util.List;

public record SkillNode(String id, String displayName, Material material, int slot, List<String> requires,
                         int maxLevel, String costFormula, String valueFormula, NodeType type) {
}
