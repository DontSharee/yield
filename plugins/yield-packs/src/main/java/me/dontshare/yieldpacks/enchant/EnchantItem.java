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

    /** "LEGENDARY Damage Enchant" in the rarity's colour - the book's own name, shared with the Enchant Market so an offer reads exactly like the book it hands over. */
    public static String displayName(EnchantType type, Rarity rarity) {
        return "<" + rarity.colorHex() + "><bold>" + rarity.displayName().toUpperCase(Locale.ROOT)
                + "</bold></" + rarity.colorHex() + "> &7" + type.displayName() + " Enchant";
    }

    /** "+26% Damage" - what one book of this rarity adds before same-type decay. */
    public static String bonusLine(EnchantType type, Rarity rarity) {
        return "&a+" + String.format(Locale.ROOT, "%.0f", EnchantService.magnitudeFor(rarity) * 100) + "% " + type.displayName();
    }

    public ItemStack create(EnchantType type, Rarity rarity) {
        String accent = "<" + rarity.colorHex() + ">";
        ItemBuilder builder = ItemBuilder.of(type.icon())
                .name(displayName(type, rarity))
                .tag(typeKey, PersistentDataType.STRING, type.name())
                .tag(rarityKey, PersistentDataType.STRING, rarity.id())
                .hideAttributes();
        MenuLore.info(
                "enchant book",
                List.of(" &7Drag into an open slot at", " &7/enchants to apply."),
                accent,
                List.of("Bonus: " + bonusLine(type, rarity))
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
