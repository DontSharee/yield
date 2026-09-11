package me.dontshare.yieldachievements.potion;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

/** Player-head consumables - right-click to drink (see PotionConsumeListener). Tagged with their own potion id so any "POTION_<STAT>_<MULTIPLIER>_<SECONDS>" string is a real, giveable item with zero pre-registration needed. */
public final class PotionItem {

    private final NamespacedKey key;

    public PotionItem(JavaPlugin plugin) {
        this.key = new NamespacedKey(plugin, "potion_id");
    }

    public NamespacedKey key() {
        return key;
    }

    public ItemStack create(PotionDefinition def) {
        String statLabel = def.stat().name().replace('_', ' ');
        String multiplierLabel = def.multiplier() == Math.rint(def.multiplier())
                ? String.valueOf((long) def.multiplier())
                : String.valueOf(def.multiplier());
        ItemBuilder builder = ItemBuilder.of(Material.PLAYER_HEAD)
                .name("<#4BD9FF><bold>" + multiplierLabel + "x " + capitalize(statLabel) + " Potion</bold>");
        List<String> data = List.of(
                "Multiplier: &fx" + multiplierLabel,
                "Duration: &f" + Formatting.format((double) def.durationSeconds()) + "s");
        MenuLore.info("potion", List.of(), MenuLore.ACCENT, data).forEach(builder::lore);
        return builder
                .lore("")
                .lore("&8Right-Click to Drink")
                .tag(key, PersistentDataType.STRING, def.id())
                .hideAttributes()
                .build();
    }

    public String potionIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private String capitalize(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (String word : lower.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return result.toString().trim();
    }
}
