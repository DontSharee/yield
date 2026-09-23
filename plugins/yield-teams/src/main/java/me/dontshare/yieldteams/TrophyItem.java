package me.dontshare.yieldteams;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldteams.data.TrophyConfig;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Builds and identifies the physical Team Trophy item - a rare ore-cube-kill drop, right-clicked to deposit into your team's balance. */
public final class TrophyItem {

    private final NamespacedKey key;

    public TrophyItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "team_trophy");
    }

    public ItemStack create(TrophyConfig config) {
        return ItemBuilder.of(config.material())
                .name(config.displayName())
                .lore("&8" + me.dontshare.yieldcore.text.Formatting.fancyFont("team trophy"))
                .lore("")
                .lore("&7Adds to your team's trophy balance.")
                .lore("")
                .lore("&8Right Click to deposit")
                .tag(key, PersistentDataType.BYTE, (byte) 1)
                .hideAttributes()
                .build();
    }

    public boolean isTrophy(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }
}
