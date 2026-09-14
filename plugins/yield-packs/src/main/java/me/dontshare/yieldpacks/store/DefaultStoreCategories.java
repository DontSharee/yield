package me.dontshare.yieldpacks.store;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.gui.PackShopGui;
import me.dontshare.yieldpacks.gui.RankupGui;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The two {@link StoreCategory} entries yield-packs contributes to its own
 * {@link StoreHubGui} - kept out of {@code YieldPacks}' own already-large
 * onEnable. Every other category (the Credits Store, Crates, ...) is a
 * satellite plugin's own concern, built and registered from that plugin's
 * own onEnable instead.
 */
public final class DefaultStoreCategories {

    private DefaultStoreCategories() {
    }

    public static StoreCategory rankup(RankupGui rankupGui) {
        return new StoreCategory("rankup", 0, DefaultStoreCategories::rankupIcon, rankupGui::open);
    }

    public static StoreCategory packShop(PackShopGui packShopGui) {
        return new StoreCategory("pack_shop", 30, DefaultStoreCategories::packShopIcon, packShopGui::open);
    }

    private static ItemStack rankupIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR).name(MenuLore.buttonName("<#B15CFF>", "RANKUP"));
        MenuLore.button("store", List.of(" &7Spend diamonds on a permanent", " &7Rank, boosting diamond income."),
                "<#B15CFF>", "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private static ItemStack packShopIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.CHEST_MINECART).name(MenuLore.buttonName("<#4BD9FF>", "PACK SHOP"));
        MenuLore.button("store", List.of(" &7Buy Packs with coins and", " &7diamonds - the source of every pet."),
                "<#4BD9FF>", "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
