package me.dontshare.yieldpacks.selector;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Builds and identifies the always-on-hand "Pack Selector" compass - see PackSelectorService/Listener for its behavior. */
public final class PackSelectorItem {

    private final NamespacedKey key;

    public PackSelectorItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "pack_selector");
    }

    /** {@code selectedPackName} should already be plain text (no color codes) - see PackSelectorService#packName. */
    public ItemStack create(String selectedPackName) {
        return ItemBuilder.of(Material.COMPASS)
                .name("<#4BD9FF><bold>Pack Selector</bold>")
                .lore("&8" + Formatting.fancyFont("pack selector"))
                .lore("")
                .lore("&7Left-Click: &fCycle selected pack")
                .lore("&7Right-Click: &fOpen selected pack")
                .lore("")
                .lore("&7Selected: &f" + selectedPackName)
                .tag(key, PersistentDataType.BYTE, (byte) 1)
                .hideAttributes()
                .build();
    }

    public boolean isPackSelector(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
