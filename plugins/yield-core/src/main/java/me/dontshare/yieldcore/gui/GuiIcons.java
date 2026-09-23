package me.dontshare.yieldcore.gui;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.List;

/** Shared filler/control icons every plugin's {@link Gui} screens draw from. */
public final class GuiIcons {

    private static final String ACCENT = "<#4BD9FF>";

    private GuiIcons() {
    }

    public static ItemStack filler() {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideAttributes().hideTooltip().build();
    }

    /** One filler built once, only ever compared against - see {@link #isFiller}. */
    private static final ItemStack FILLER = filler();

    /** Whether {@code item} is the plain frame pane (and not, say, a coloured marker or a named pane). */
    public static boolean isFiller(ItemStack item) {
        return item != null && item.getType() == Material.GRAY_STAINED_GLASS_PANE && item.isSimilar(FILLER);
    }

    /**
     * A coloured pane with no tooltip, used to point at something - above
     * the weapon you hold, beside the one you can buy next. Unlike the
     * frame filler it is left where a screen puts it.
     */
    public static ItemStack marker(Material pane) {
        return ItemBuilder.of(pane).hideAttributes().hideTooltip().build();
    }

    /** The standard back button - top-left corner, wired by the screen to wherever "back" is. */
    public static ItemStack backButton(String destination) {
        ItemBuilder builder = ItemBuilder.of(Material.ENDER_PEARL).name(MenuLore.buttonName(ACCENT, "Go Back"));
        MenuLore.button("navigation", List.of("&7Return to " + destination + "."), ACCENT, "Click to go back")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /** The standard close button - wire its click handler to {@code (clicker, event) -> clicker.closeInventory()}. */
    public static ItemStack closeButton() {
        ItemBuilder builder = ItemBuilder.of(Material.BARRIER).name(MenuLore.buttonName("&c", "Close Menu"));
        MenuLore.button("navigation", List.of(), "&c", "Click to close this menu")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /**
     * A page-turn arrow - a Tipped Arrow of Luck (previous) or Healing
     * (next), potion tooltip suppressed via hideAttributes(). {@code
     * enabled} false is a plain frame pane instead of a dead button (still
     * safe to give it a click handler - callers check {@code hasPrevious()}/
     * {@code hasNext()} themselves before acting).
     */
    public static ItemStack pageArrow(boolean forward, boolean enabled) {
        // No page that way: just frame, rather than a dead button.
        if (!enabled) {
            return filler();
        }
        ItemBuilder builder = ItemBuilder.of(tippedArrow(forward ? PotionType.HEALING : PotionType.LUCK));
        builder.name(MenuLore.buttonName(ACCENT, forward ? "Next Page" : "Previous Page"));
        MenuLore.button("navigation", List.of(), ACCENT, forward ? "Click to go to the next page" : "Click to go to the previous page")
                .forEach(builder::lore);
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
