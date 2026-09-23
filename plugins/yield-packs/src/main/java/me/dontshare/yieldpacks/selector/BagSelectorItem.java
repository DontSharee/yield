package me.dontshare.yieldpacks.selector;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Builds and identifies the always-on-hand "Bag" shortcut item - see BagSelectorListener for its behavior. */
public final class BagSelectorItem {

    private final NamespacedKey key;

    public BagSelectorItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "bag_selector");
    }

    public ItemStack create() {
        return ItemBuilder.of(Material.CHEST)
                .name(MenuLore.name(MenuLore.ACCENT, "Pet Bag"))
                .lore("&8" + me.dontshare.yieldcore.text.Formatting.fancyFont("player item"))
                .lore("")
                .lore("&8Click to open your pets")
                .tag(key, PersistentDataType.BYTE, (byte) 1)
                .hideAttributes()
                .build();
    }

    public boolean isBagSelector(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
