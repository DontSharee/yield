package me.dontshare.yieldcore.item;

import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for custom items. Display name and lore are parsed via
 * {@link Text} - MiniMessage tags (hex colors, gradients, etc. - see
 * https://docs.advntr.dev/minimessage/format.html) and legacy "&amp;" codes
 * (e.g. {@code &7}, {@code &l}) can both be used, and custom data is
 * stored via {@link org.bukkit.persistence.PersistentDataContainer} rather
 * than raw NBT editing - no external NBT library needed.
 * <p>
 * Lore lines have italics disabled by default: un-styled Minecraft lore
 * renders italic gray, which clashes with custom styling.
 */
public final class ItemBuilder {

    private final ItemStack item;
    private final ItemMeta meta;
    private final List<Component> lore = new ArrayList<>();

    private ItemBuilder(Material material) {
        this.item = new ItemStack(material);
        this.meta = item.getItemMeta();
    }

    private ItemBuilder(ItemStack existing) {
        this.item = existing.clone();
        this.meta = item.getItemMeta();
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(material);
    }

    /** Starts from an already-built item (e.g. a HeadDatabase skull) instead of a fresh one, preserving its existing meta. */
    public static ItemBuilder of(ItemStack existing) {
        return new ItemBuilder(existing);
    }

    public ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    public ItemBuilder name(String text) {
        meta.displayName(style(Text.parse(text)));
        return this;
    }

    public ItemBuilder lore(String text) {
        lore.add(style(Text.parse(text)));
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        lines.forEach(this::lore);
        return this;
    }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        meta.addEnchant(enchantment, level, true);
        return this;
    }

    public ItemBuilder unbreakable(boolean unbreakable) {
        meta.setUnbreakable(unbreakable);
        return this;
    }

    public ItemBuilder modelData(int modelData) {
        meta.setCustomModelData(modelData);
        return this;
    }

    /** Hides the default attribute/enchant/unbreakable tooltip lines Minecraft appends automatically. */
    public ItemBuilder hideAttributes() {
        meta.addItemFlags(ItemFlag.values());
        return this;
    }

    /** Suppresses the tooltip box entirely on hover - for GUI filler that shouldn't show anything, not even a blank box. */
    public ItemBuilder hideTooltip() {
        meta.setHideTooltip(true);
        return this;
    }

    /** Stores custom plugin data on the item via PersistentDataContainer. */
    public <T, Z> ItemBuilder tag(NamespacedKey key, PersistentDataType<T, Z> type, Z value) {
        meta.getPersistentDataContainer().set(key, type, value);
        return this;
    }

    public ItemStack build() {
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Not italic unless the text asks to be. Setting it outright would also
     * undo a line that is italic as a whole - "&7&oYou cannot claim this
     * gift yet." parses to one component with italic already on it.
     */
    private static Component style(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
