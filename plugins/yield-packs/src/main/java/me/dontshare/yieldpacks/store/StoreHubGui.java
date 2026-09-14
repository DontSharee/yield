package me.dontshare.yieldpacks.store;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldpacks.gui.GuiIcons;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * "The Store" - the Buycraft/Tebex-style real-money storefront, ONE
 * persistent screen tabbed by category (Ranks, Gamepasses, Bundles,
 * Exclusive Crates, and anything else a plugin registers later - see
 * {@code YieldPacks#registerStoreCategory}; yield-achievements owns the
 * Credits Store and registers all of the current tabs). Deliberately NOT
 * where Rankup, the Credits-based Crate Shop, or the Pack Shop live - those
 * are unrelated in-game-currency grind systems with their own commands, not
 * things a real webstore would sell. Row 0 is the category selector;
 * clicking a tab repaints {@link #CONTENT_SLOTS} of THIS SAME open
 * inventory to that category's real, live content (see {@link
 * StoreCategory.CategoryRenderer}) - selecting a tab, paging, and
 * purchasing never opens a second Gui.
 */
public final class StoreHubGui {

    private static final int TOTAL_ROWS = 6;
    private static final int ROW_WIDTH = 7;
    private static final int MAX_CATEGORIES = ROW_WIDTH;
    /** Every slot below the category row - what a {@link StoreCategory.CategoryRenderer} owns and must fully repaint on every call. */
    public static final List<Integer> CONTENT_SLOTS = IntStream.range(9, TOTAL_ROWS * 9).boxed().toList();

    private final GuiManager guiManager;
    private final Supplier<List<StoreCategory>> categories;
    /** Which category each player is currently looking at - only meaningful while the Store is actually their open screen; a fresh {@link #open} always starts back at the first category. */
    private final Map<UUID, String> selectedCategory = new ConcurrentHashMap<>();

    public StoreHubGui(GuiManager guiManager, Supplier<List<StoreCategory>> categories) {
        this.guiManager = guiManager;
        this.categories = categories;
    }

    public void open(Player player) {
        List<StoreCategory> sorted = sortedCategories();
        if (sorted.isEmpty()) {
            return;
        }
        StoreCategory first = sorted.get(0);
        selectedCategory.put(player.getUniqueId(), first.id());

        var builder = Gui.builder(TOTAL_ROWS, "Store");
        builder.fill(IntStream.range(0, 9), GuiIcons.filler());
        placeCategoryButtons(builder::item, sorted, first.id());
        Gui gui = builder.build();
        first.renderer().render(player, gui, this);
        guiManager.open(player, gui);
    }

    private void selectCategory(Player player, StoreCategory category) {
        selectedCategory.put(player.getUniqueId(), category.id());
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        placeCategoryButtons(gui::set, sortedCategories(), category.id());
        category.renderer().render(player, gui, this);
    }

    /**
     * Redraws whichever category the player is currently on, in place - for
     * a {@link StoreCategory.CategoryRenderer} to call after an action (a
     * purchase, a page turn) changes what it should show, without resetting
     * which tab is selected. No-op if the Store isn't (or is no longer)
     * their actual open screen.
     */
    public void refreshContent(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        List<StoreCategory> sorted = sortedCategories();
        String selectedId = selectedCategory.get(player.getUniqueId());
        sorted.stream().filter(c -> c.id().equals(selectedId)).findFirst()
                .ifPresent(category -> category.renderer().render(player, gui, this));
    }

    private List<StoreCategory> sortedCategories() {
        return categories.get().stream()
                .sorted(Comparator.comparingInt(StoreCategory::sortOrder))
                .limit(MAX_CATEGORIES)
                .toList();
    }

    /** {@code place} is either a fresh {@code GuiBuilder::item} (first open) or a live {@code Gui::set} (switching tabs) - same 3-arg shape either way. */
    private void placeCategoryButtons(CategoryButtonPlacer place, List<StoreCategory> sorted, String selectedId) {
        int startSlot = Math.max(0, (9 - sorted.size()) / 2);
        for (int i = 0; i < sorted.size(); i++) {
            StoreCategory category = sorted.get(i);
            place.place(startSlot + i, category.buttonIcon().apply(category.id().equals(selectedId)),
                    (clicker, event) -> selectCategory(clicker, category));
        }
    }

    @FunctionalInterface
    private interface CategoryButtonPlacer {
        void place(int slot, org.bukkit.inventory.ItemStack item, me.dontshare.yieldcore.gui.GuiClickHandler handler);
    }
}
