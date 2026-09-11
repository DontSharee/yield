package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.List;

/** Shared filler/control icons for yield-packs' Gui screens. */
public final class GuiIcons {

    private static final String ACCENT = "<#4BD9FF>";

    private GuiIcons() {
    }

    public static ItemStack filler() {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideAttributes().hideTooltip().build();
    }

    /** The standard close button - wire its click handler to {@code (clicker, event) -> clicker.closeInventory()}. */
    public static ItemStack closeButton() {
        ItemBuilder builder = ItemBuilder.of(Material.BARRIER).name(MenuLore.buttonName(ACCENT, "CLOSE"));
        MenuLore.button("navigation", List.of(" &7Close &fthis&7 menu"), ACCENT, "Click to Close")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /**
     * A page-turn arrow - a Tipped Arrow of Luck (previous) or Healing
     * (next), potion tooltip suppressed via hideAttributes(). {@code
     * enabled} false renders a grayed-out, non-functional version (still
     * safe to give it a click handler - it just won't have anywhere to go,
     * so callers should check {@code hasPrevious()}/{@code hasNext()}
     * themselves before acting).
     */
    public static ItemStack pageArrow(boolean forward, boolean enabled) {
        String label = forward ? "NEXT" : "PREVIOUS";
        ItemBuilder builder = enabled
                ? ItemBuilder.of(tippedArrow(forward ? PotionType.HEALING : PotionType.LUCK))
                : ItemBuilder.of(Material.GRAY_DYE);
        builder.name(enabled ? MenuLore.buttonName(ACCENT, label) : "&7" + label);
        if (enabled) {
            MenuLore.button("navigation", List.of(" &7Go to the " + (forward ? "&fnext" : "&fprevious") + "&7 page"),
                    ACCENT, "Click to Turn Page"
            ).forEach(builder::lore);
        } else {
            MenuLore.info("navigation", List.of(" &7No " + (forward ? "more" : "previous") + " pages"), ACCENT, List.of())
                    .forEach(builder::lore);
        }
        return builder.hideAttributes().build();
    }

    private static ItemStack tippedArrow(PotionType potionType) {
        ItemStack arrow = new ItemStack(Material.TIPPED_ARROW);
        if (arrow.getItemMeta() instanceof PotionMeta potionMeta) {
            potionMeta.setBasePotionType(potionType);
            arrow.setItemMeta(potionMeta);
        }
        return arrow;
    }
}
