package me.dontshare.yieldpacks.store;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldpacks.gui.GuiIcons;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * "The Store" - the single landing screen for every purchase/progression
 * destination on the server (Rankup, the Credits Store, Crates, the Pack
 * Shop, and anything else a plugin registers later - see
 * {@code YieldPacks#registerStoreCategory}), rendered as one row of
 * category buttons. A click just opens that category's own existing GUI -
 * this never renders their content itself, so none of those screens needed
 * to change shape to live in here. Reachable via /store, /buy, and (once a
 * category exists for it) any other command that wants to land here instead
 * of jumping straight to one destination.
 */
public final class StoreHubGui {

    private static final int ROW_START = 10;
    private static final int ROW_WIDTH = 7;
    private static final int MAX_CATEGORIES = ROW_WIDTH;
    private static final int CLOSE_SLOT = 22;

    private final GuiManager guiManager;
    private final Supplier<List<StoreCategory>> categories;

    public StoreHubGui(GuiManager guiManager, Supplier<List<StoreCategory>> categories) {
        this.guiManager = guiManager;
        this.categories = categories;
    }

    public void open(Player player) {
        List<StoreCategory> sorted = categories.get().stream()
                .sorted(Comparator.comparingInt(StoreCategory::sortOrder))
                .limit(MAX_CATEGORIES)
                .toList();

        var builder = Gui.builder(3, "Store");
        builder.fill(IntStream.range(0, 27), GuiIcons.filler());

        int startSlot = ROW_START + Math.max(0, (ROW_WIDTH - sorted.size()) / 2);
        for (int i = 0; i < sorted.size(); i++) {
            StoreCategory category = sorted.get(i);
            builder.item(startSlot + i, category.icon().get(), (clicker, event) -> category.opener().accept(clicker));
        }

        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }
}
