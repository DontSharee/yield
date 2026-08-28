package me.dontshare.yieldpacks.data;

/**
 * A rarity tier, entirely config-driven (see packs.yml). {@code luckExponent}
 * controls how strongly this tier's roll weight is boosted by luck - 0.0
 * (typically "common") is unaffected by luck, higher values are boosted
 * more the higher the player's current luck multiplier is.
 */
public record Rarity(String id, String displayName, String colorHex, int sortOrder, double luckExponent) {
}
