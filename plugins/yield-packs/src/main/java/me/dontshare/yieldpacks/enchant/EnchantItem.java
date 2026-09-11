package me.dontshare.yieldpacks.enchant;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.data.Rarity;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

/** Builds and identifies an Enchant Book - a real, droppable item carrying an {@link EnchantType} and a {@link Rarity}, dragged into an {@code EnchantGui} slot to apply its bonus. */
public final class EnchantItem {

    private final NamespacedKey typeKey;
    private final NamespacedKey rarityKey;

    public EnchantItem(JavaPlugin plugin) {
        this.typeKey = new NamespacedKey(plugin, "enchant_type");
        this.rarityKey = new NamespacedKey(plugin, "enchant_rarity");
    }

    public ItemStack create(EnchantType type, Rarity rarity) {
        double magnitude = EnchantService.magnitudeFor(rarity);
        String accent = "<" + rarity.colorHex() + ">";
        ItemBuilder builder = ItemBuilder.of(type.icon())
                .name(accent + "<bold>" + rarity.displayName().toUpperCase(Locale.ROOT)
                        + "</bold></" + rarity.colorHex() + "> &7" + type.displayName() + " Enchant")
                .tag(typeKey, PersistentDataType.STRING, type.name())
                .tag(rarityKey, PersistentDataType.STRING, rarity.id())
                .hideAttributes();
        MenuLore.info(
                "enchant book",
                List.of(" &7Drag into an open slot at", " &7/enchants to apply."),
                accent,
                List.of("Bonus: &a+" + String.format(Locale.ROOT, "%.0f", magnitude * 100) + "% " + type.displayName())
        ).forEach(builder::lore);
        return builder.build();
    }

    public boolean isEnchantBook(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(typeKey, PersistentDataType.STRING);
    }

    public EnchantType typeOf(ItemStack item) {
        if (!isEnchantBook(item)) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        try {
            return raw == null ? null : EnchantType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String rarityIdOf(ItemStack item) {
        if (!isEnchantBook(item)) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(rarityKey, PersistentDataType.STRING);
    }
}
