package me.dontshare.yieldpacks.item;

import me.arcaniax.hdb.api.HeadDatabaseAPI;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.fusion.FusionTier;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
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

    /**
     * The HeadDatabase head with this id, or {@code fallback} as a plain
     * item when HeadDatabase isn't installed or the id doesn't resolve.
     * <p>
     * For the things that are rendered in the WORLD rather than in a menu -
     * the egg on a station, the clutch that hatches in front of a player -
     * where there is no ItemDefinition to hang the lookup off, just an id
     * from config.
     */
    public ItemStack headOrFallback(String headDatabaseId, org.bukkit.Material fallback) {
        if (headDatabaseId != null && !headDatabaseId.isBlank() && headDatabaseApi != null) {
            ItemStack head = headDatabaseApi.getItemHead(headDatabaseId);
            if (head != null) {
                return head;
            }
        }
        return new ItemStack(fallback);
    }

    /** An ItemBuilder pre-seeded with the pet's base icon - chain .name()/.lore()/etc on top. */
    public ItemBuilder baseIcon(ItemDefinition item) {
        ItemBuilder builder = null;
        if (item.headDatabaseId() != null && headDatabaseApi != null) {
            ItemStack head = headDatabaseApi.getItemHead(item.headDatabaseId());
            if (head != null) {
                builder = ItemBuilder.of(head);
            }
        }
        if (builder == null) {
            builder = ItemBuilder.of(item.material());
            if (item.customModelData() != null) {
                builder.modelData(item.customModelData());
            }
        }
        if (item.fusionTier() != FusionTier.NORMAL) {
            // A cheap, harmless enchant purely for the glint shimmer - every
            // existing caller already calls hideAttributes(), which hides
            // the "Unbreaking I" tooltip line but not the glint itself, so
            // fused pets shimmer everywhere they render for free.
            builder.enchant(Enchantment.UNBREAKING, 1);
        }
        return builder;
    }
}
