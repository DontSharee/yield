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
 * <p>
 * {@code headDatabaseId} is what the egg actually LOOKS like wherever it is
 * rendered in the world - the station it sits on and the clutch that hatches
 * in front of the player. It falls back to {@code material} when HeadDatabase
 * isn't installed or the id doesn't resolve, so a server without the plugin
 * still shows a real egg (just a vanilla one), and defaults to packs.yml's
 * own {@code egg-head-database-id} when a pack doesn't name its own.
 */
public record PackDefinition(String id, String displayName, Material material, Integer customModelData,
                              String headDatabaseId, long coinCost, long diamondCost, int sortOrder,
                              List<PackPoolEntry> pool, double shopWeight, double shopLuckExponent,
                              int minStock, int maxStock) {
}
