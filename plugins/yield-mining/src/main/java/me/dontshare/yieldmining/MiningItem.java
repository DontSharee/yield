package me.dontshare.yieldmining;

import me.dontshare.yieldcore.item.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Builds and identifies the admin "place to create a mining spot" item - see MiningService#onPlace for the placement gesture. */
public final class MiningItem {

    private final NamespacedKey materialKey;

    public MiningItem(JavaPlugin plugin) {
        this.materialKey = new NamespacedKey(plugin, "mining_spot_material");
    }

    public ItemStack create(Material oreMaterial) {
        return ItemBuilder.of(oreMaterial)
                .name("<#57D9A3><bold>Mining Spot</bold></#57D9A3> <gray>(" + displayName(oreMaterial) + ")</gray>")
                .lore("&7Place to create a packet-only")
                .lore("&7mining spot - minable by everyone,")
                .lore("&7never a real block.")
                .tag(materialKey, PersistentDataType.STRING, oreMaterial.name())
                .build();
    }

    /** The ore material this item places, or null for anything else - including a plain block of the same material. */
    public Material materialOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(materialKey, PersistentDataType.STRING);
        return raw == null ? null : Material.matchMaterial(raw);
    }

    private String displayName(Material material) {
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
}
