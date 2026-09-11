package me.dontshare.yieldpacks.shard;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/** Builds and identifies physical Shard items - see ShardConsumeListener for the right-click-to-consume gesture. */
public final class ShardItem {

    private static final String ACCENT = "<#B15CFF>";

    private final NamespacedKey typeKey;
    private final NamespacedKey perfectKey;

    public ShardItem(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.typeKey = new NamespacedKey(plugin, "shard_type");
        this.perfectKey = new NamespacedKey(plugin, "shard_perfect");
    }

    public ItemStack create(ShardType type, boolean perfect) {
        String label = capitalize(type.name());
        String bonus = perfect ? "+10%" : "+1%";
        ItemBuilder builder = ItemBuilder.of(materialFor(type))
                .name(perfect
                        ? "<gradient:#FFD700:#FF66CC:#00E5FF><bold>Perfect " + label + " Shard</bold></gradient>"
                        : ACCENT + "<bold>" + label + " Shard</bold>");
        MenuLore.info("shard", List.of(), perfect ? "<gold>" : ACCENT, List.of(
                "&7Permanent " + label.toLowerCase(Locale.ROOT) + " bonus: &b" + bonus,
                "&7Never expires, never resets."
        )).forEach(builder::lore);
        builder.lore("").lore("&8Right-Click to Consume");
        if (perfect) {
            builder.enchant(Enchantment.UNBREAKING, 1);
        }
        return builder
                .tag(typeKey, PersistentDataType.STRING, type.name())
                .tag(perfectKey, PersistentDataType.BYTE, (byte) (perfect ? 1 : 0))
                .hideAttributes()
                .build();
    }

    /** The shard type this item is, or null for anything else - including a plain material that happens to match its icon. */
    public ShardType typeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return ShardType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isPerfect(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Byte flag = item.getItemMeta().getPersistentDataContainer().get(perfectKey, PersistentDataType.BYTE);
        return flag != null && flag == 1;
    }

    private Material materialFor(ShardType type) {
        return switch (type) {
            case DAMAGE -> Material.BLAZE_POWDER;
            case COINS -> Material.GOLD_NUGGET;
            case GEMS -> Material.EMERALD;
            case LUCK -> Material.RABBIT_FOOT;
            case ATTACK_SPEED -> Material.FEATHER;
            case CRIT_CHANCE -> Material.ARROW;
        };
    }

    private String capitalize(String raw) {
        return raw.charAt(0) + raw.substring(1).toLowerCase(Locale.ROOT);
    }
}
