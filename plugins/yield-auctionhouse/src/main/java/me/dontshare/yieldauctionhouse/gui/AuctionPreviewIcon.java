package me.dontshare.yieldauctionhouse.gui;

import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a display copy of a real item with a few extra lore lines appended
 * UNDERNEATH whatever lore it already had (a pet's own stats, a potion's own
 * description, ...) - unlike {@code ItemBuilder}, which replaces an item's
 * lore outright, this is specifically for previewing a real traded item
 * (a listing, a claim) where losing its own description would hide exactly
 * the information a buyer most wants to see.
 */
final class AuctionPreviewIcon {

    private AuctionPreviewIcon() {
    }

    static ItemStack build(ItemStack original, List<String> extraLines) {
        ItemStack display = original != null ? original.clone() : new ItemStack(Material.BARRIER);
        display.setAmount(Math.max(1, Math.min(64, display.getAmount())));
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        for (String line : extraLines) {
            lore.add(Text.parse(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }
}
