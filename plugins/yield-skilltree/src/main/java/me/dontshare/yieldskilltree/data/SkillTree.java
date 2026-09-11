package me.dontshare.yieldskilltree.data;

import java.util.Map;

public record SkillTree(String id, Currency currency, Map<String, SkillNode> nodes) {
}
