package me.dontshare.yieldquests.data;

import org.bukkit.Material;

import java.util.Map;

/** A named group of quests (e.g. "combat", "economy") - exactly one {@link QuestDifficulty} tier of it can be actively pursued per player per day. */
public record QuestCategory(String id, String displayName, Material icon, Map<QuestDifficulty, QuestDefinition> tiers) {
}
