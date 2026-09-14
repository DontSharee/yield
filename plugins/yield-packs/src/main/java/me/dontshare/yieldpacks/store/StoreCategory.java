package me.dontshare.yieldpacks.store;

import me.dontshare.yieldcore.gui.Gui;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Function;

/**
 * One tab inside {@link StoreHubGui} - a plugin registers one of these (see
 * {@code YieldPacks#registerStoreCategory}) rather than the hub importing
 * every contributing GUI class directly, so yield-packs never needs a
 * compile-time dependency on yield-achievements or yield-spawnnpcs (same
 * registry-of-providers shape as {@code registerDiamondMultiplierProvider}
 * and every other cross-plugin hook YieldPacks already exposes).
 * {@code buttonIcon} is called fresh on every render (never cached), fed
 * whether THIS tab is the one currently selected, so a category can
 * highlight its own button while active.
 */
public record StoreCategory(String id, int sortOrder, Function<Boolean, ItemStack> buttonIcon, CategoryRenderer renderer) {

    /**
     * Paints this category's entire content region - every slot in
     * {@link StoreHubGui#CONTENT_SLOTS} - directly into the shared,
     * ALREADY-OPEN hub {@code gui} via {@link Gui#set}, never opening a
     * second Gui/inventory. Must repaint every content slot on every call
     * (filler included) since switching tabs doesn't clear whatever the
     * previous category left behind. Call {@link StoreHubGui#refreshContent}
     * after an action (a purchase, a page turn) to redraw in place without
     * resetting which tab is selected.
     */
    public interface CategoryRenderer {
        void render(Player player, Gui gui, StoreHubGui hub);
    }
}
