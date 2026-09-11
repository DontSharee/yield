package me.dontshare.yieldmining;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Builds the real, physical item a mining spot hands out - a proper name/lore, not a bare vanilla stack. */
final class OreDropItemFactory {

    private record Style(String displayName, String accent, String flavor) {
    }

    private OreDropItemFactory() {
    }

    static ItemStack create(Material dropMaterial, int amount) {
        Style style = STYLES.getOrDefault(dropMaterial, new Style(prettyName(dropMaterial), "<#B8B8B8>", "A raw mining resource."));
        ItemStack item = ItemBuilder.of(dropMaterial)
                .amount(amount)
                .name(style.accent() + "<bold>" + style.displayName() + "</bold>")
                .lore(MenuLore.info("ore", List.of("&7" + style.flavor()), style.accent(), List.of()))
                .hideAttributes()
                .build();
        return item;
    }

    private static String prettyName(Material material) {
        String[] words = material.name().split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(word.charAt(0)).append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return builder.toString();
    }

    private static final java.util.Map<Material, Style> STYLES = java.util.Map.of(
            Material.COAL, new Style("Coal", "<#5B5B5B>", "A dense, sooty chunk - burns hot."),
            Material.RAW_COPPER, new Style("Raw Copper", "<#E5945B>", "Freshly broken from the vein, still rough."),
            Material.RAW_IRON, new Style("Raw Iron", "<#D8CFC4>", "Unrefined, but worth good coin as-is."),
            Material.REDSTONE, new Style("Redstone Dust", "<#C62B2B>", "Faintly pulses with restless energy."),
            Material.LAPIS_LAZULI, new Style("Lapis Lazuli", "<#3B5FE0>", "Deep blue, flecked with gold."),
            Material.RAW_GOLD, new Style("Raw Gold", "<#F2C744>", "Heavy, gleaming, unmistakably valuable."),
            Material.DIAMOND, new Style("Diamond", "<#63E3D6>", "Cold, clear, and cut by nothing but itself."),
            Material.EMERALD, new Style("Emerald", "<#3FCE6E>", "A rare, brilliant green.")
    );
}
