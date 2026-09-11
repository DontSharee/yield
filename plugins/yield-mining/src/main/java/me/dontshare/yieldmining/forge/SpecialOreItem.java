package me.dontshare.yieldmining.forge;

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

/** Builds and identifies a "Special Ore" - a rarer, RNG-rolled variant of a normal mined ore, carrying its own Multiplier stat, combined at /forge. */
public final class SpecialOreItem {

    private final NamespacedKey materialKey;
    private final NamespacedKey multiplierKey;
    private final NamespacedKey tierKey;

    public SpecialOreItem(JavaPlugin plugin) {
        this.materialKey = new NamespacedKey(plugin, "special_ore_material");
        this.multiplierKey = new NamespacedKey(plugin, "special_ore_multiplier");
        this.tierKey = new NamespacedKey(plugin, "special_ore_tier");
    }

    public ItemStack create(Material oreMaterial, double multiplier, SpecialOreTier tier) {
        ItemBuilder builder = ItemBuilder.of(oreMaterial)
                .name(MenuLore.infoName(MenuLore.ACCENT, prettyName(oreMaterial).toUpperCase(Locale.ROOT)) + " &8[Ore]")
                .tag(materialKey, PersistentDataType.STRING, oreMaterial.name())
                .tag(multiplierKey, PersistentDataType.DOUBLE, multiplier)
                .tag(tierKey, PersistentDataType.STRING, tier.id())
                .hideAttributes();
        MenuLore.info(
                "special ore",
                List.of(" &7Merge with other ores at", " &7/forge to craft absurd items."),
                MenuLore.ACCENT,
                List.of(
                        "Multiplier: &a" + String.format(Locale.ROOT, "%.3f", multiplier) + "x",
                        "Chance: &71 / " + Formatting.format(tier.oneIn())
                )
        ).forEach(builder::lore);
        return builder.build();
    }

    public boolean isSpecialOre(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(multiplierKey, PersistentDataType.DOUBLE);
    }

    public double multiplierOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Double value = item.getItemMeta().getPersistentDataContainer().get(multiplierKey, PersistentDataType.DOUBLE);
        return value == null ? 0 : value;
    }

    private String prettyName(Material material) {
        String[] words = material.name().split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }
}
