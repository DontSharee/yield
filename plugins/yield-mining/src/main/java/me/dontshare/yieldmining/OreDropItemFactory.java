package me.dontshare.yieldmining;

import me.dontshare.yieldcore.item.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Builds the real, physical item a mining spot hands out - a proper coloured
 * name, not a bare vanilla stack. No flavour text: every item on the server
 * shows facts or nothing, so ore is just its name.
 */
final class OreDropItemFactory {

    private record Style(String displayName, String accent) {
    }

    private OreDropItemFactory() {
    }

    static ItemStack create(Material dropMaterial, int amount) {
        Style style = STYLES.getOrDefault(dropMaterial, new Style(prettyName(dropMaterial), "<#B8B8B8>"));
        return ItemBuilder.of(dropMaterial)
                .amount(amount)
                .name(style.accent() + "<bold>" + style.displayName() + "</bold>")
                .hideAttributes()
                .build();
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
            Material.COAL, new Style("Coal", "<#5B5B5B>"),
            Material.RAW_COPPER, new Style("Raw Copper", "<#E5945B>"),
            Material.RAW_IRON, new Style("Raw Iron", "<#D8CFC4>"),
            Material.REDSTONE, new Style("Redstone Dust", "<#C62B2B>"),
            Material.LAPIS_LAZULI, new Style("Lapis Lazuli", "<#3B5FE0>"),
            Material.RAW_GOLD, new Style("Raw Gold", "<#F2C744>"),
            Material.DIAMOND, new Style("Diamond", "<#63E3D6>"),
            Material.EMERALD, new Style("Emerald", "<#3FCE6E>")
    );
}
