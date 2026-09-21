package me.dontshare.yieldpacks.selector;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Builds and identifies the always-on-hand "Egg Book" compass - see PackSelectorService/Listener for its behavior. */
public final class PackSelectorItem {

    private final NamespacedKey key;

    public PackSelectorItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "pack_selector");
    }

    /** {@code lastEggName} should already be plain text (no color codes) - see PackSelectorService#packName. */
    public ItemStack create(String lastEggName) {
        return ItemBuilder.of(Material.COMPASS)
                .name("<#4BD9FF><bold>Egg Book</bold>")
                .lore("&8" + Formatting.fancyFont("egg book"))
                .lore("")
                .lore("&7Click: &fBrowse every egg")
                .lore("&7and what hatches from it.")
                .lore("")
                .lore("&7Last hatched: &f" + lastEggName)
                .tag(key, PersistentDataType.BYTE, (byte) 1)
                .hideAttributes()
                .build();
    }

    public boolean isPackSelector(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
