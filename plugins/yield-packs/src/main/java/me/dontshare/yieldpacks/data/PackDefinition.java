package me.dontshare.yieldpacks.data;

import org.bukkit.Material;

import java.util.List;

/**
 * A pack definition, entirely config-driven (see packs.yml). {@code
 * shopWeight}/{@code shopLuckExponent} control how likely this pack is to
 * appear in a player's rotating shop stock (see
 * {@code me.dontshare.yieldpacks.shop.ShopStockService}) - a heavily
 * weighted pack with a 0 luck exponent shows up in nearly every roll
 * regardless of luck; a low-weight pack with a high exponent is rare and
 * boosted by the player's current luck, same shape as pet rarity odds.
 * {@code minStock}/{@code maxStock} bound how many units of it a given
 * roll makes available.
 */
public record PackDefinition(String id, String displayName, Material material, Integer customModelData,
                              long coinCost, long diamondCost, int sortOrder, List<PackPoolEntry> pool,
                              double shopWeight, double shopLuckExponent, int minStock, int maxStock) {
}
