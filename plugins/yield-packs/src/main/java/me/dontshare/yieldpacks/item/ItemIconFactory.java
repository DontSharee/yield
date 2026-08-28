package me.dontshare.yieldpacks.item;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldpacks.data.ItemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * Resolves a pet's icon - a HeadDatabase player head when the pet defines a
 * {@code headDatabaseId} and the HeadDatabase plugin is installed, falling
 * back to its plain {@code material} (+ optional custom model data)
 * otherwise. Centralized here so every screen that renders a pet icon makes
 * this decision the same way.
 */
public final class ItemIconFactory {

    private final HeadDatabaseAPI headDatabaseApi;

    public ItemIconFactory() {
        this.headDatabaseApi = Bukkit.getPluginManager().getPlugin("HeadDatabase") != null
                ? new HeadDatabaseAPI()
                : null;
    }

    /** An ItemBuilder pre-seeded with the pet's base icon - chain .name()/.lore()/etc on top. */
    public ItemBuilder baseIcon(ItemDefinition item) {
        if (item.headDatabaseId() != null && headDatabaseApi != null) {
            ItemStack head = headDatabaseApi.getItemHead(item.headDatabaseId());
            if (head != null) {
                return ItemBuilder.of(head);
            }
        }
        ItemBuilder builder = ItemBuilder.of(item.material());
        if (item.customModelData() != null) {
            builder.modelData(item.customModelData());
        }
        return builder;
    }
}
