package me.dontshare.yieldpacks.store;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One clickable destination inside {@link StoreHubGui} - a plugin registers
 * one of these (see {@code YieldPacks#registerStoreCategory}) rather than
 * the hub importing every contributing GUI class directly, so yield-packs
 * never needs a compile-time dependency on yield-achievements or
 * yield-spawnnpcs (same registry-of-providers shape as
 * {@code registerDiamondMultiplierProvider} and every other cross-plugin
 * hook YieldPacks already exposes). {@code icon} is called fresh on every
 * render (not cached) so it can reflect live state (afford-checks, current
 * rank, etc.) exactly like every other GUI icon builder in this codebase.
 */
public record StoreCategory(String id, int sortOrder, Supplier<ItemStack> icon, Consumer<Player> opener) {
}
