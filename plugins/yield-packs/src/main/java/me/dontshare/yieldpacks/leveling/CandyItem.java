package me.dontshare.yieldpacks.leveling;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/** Builds and identifies physical Pet Candy items - see PetLevelingService for the feed-on-click gesture (Bag GUI). */
public final class CandyItem {

    private final NamespacedKey key;

    public CandyItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "pet_candy_id");
    }

    public ItemStack create(Candy candy) {
        ItemBuilder builder = ItemBuilder.of(candy.material())
                .name(MenuLore.name("<#FFB6E1>", candy.displayName()));
        MenuLore.info("candy", List.of(), MenuLore.ACCENT, List.of("Level Cap: &f+" + candy.levelCapBonus()))
                .forEach(builder::lore);
        return builder
                .lore("")
                .lore("&8Hold and Click a Pet to Feed")
                .tag(key, PersistentDataType.STRING, candy.id())
                .hideAttributes()
                .build();
    }

    /** The candy id tagged on this item, or null if it isn't a candy item at all. */
    public String idFor(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }
}
