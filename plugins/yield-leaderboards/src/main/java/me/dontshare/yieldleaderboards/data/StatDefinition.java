package me.dontshare.yieldleaderboards.data;

/** One rankable stat - {@code field} is the dotted path into the shared "playerData" Mongo document (e.g. "packs.coins"). */
public record StatDefinition(String id, String field, StatType type) {
}
