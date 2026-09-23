package me.dontshare.yieldachievements.gui;

import me.dontshare.yieldachievements.MilestoneService;
import me.dontshare.yieldachievements.data.MilestoneCategory;
import me.dontshare.yieldachievements.data.MilestoneTier;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The /milestones hub - one icon per configured category (fully data-driven,
 * see milestones.yml - adding a category is a config change only). Click a
 * category to open its own paginated tier list ({@link MilestoneCategoryGui}).
 */
public final class MilestonesGui {

    private static final int TOTAL_ROWS = 6;
    private static final int CLOSE_SLOT = 49;

    private final Supplier<Map<String, MilestoneCategory>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final MilestoneService service;
    private final GuiManager guiManager;
    private final MilestoneCategoryGui categoryGui;

    public MilestonesGui(Supplier<Map<String, MilestoneCategory>> content, PlayerDataStore<PackPlayerProfile> store,
                          MilestoneService service, GuiManager guiManager, MilestoneCategoryGui categoryGui) {
        this.content = content;
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
        this.categoryGui = categoryGui;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        var builder = Gui.builder(TOTAL_ROWS, "Milestones");
        builder.fill(java.util.stream.IntStream.range(45, 54).filter(s -> s != CLOSE_SLOT), GuiIcons.filler());
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        // Centred seven to a row under a border row, so a handful of
        // categories sit in the middle instead of hugging the left edge.
        builder.fill(java.util.stream.IntStream.range(0, 45), GuiIcons.filler());
        List<MilestoneCategory> categories = new java.util.ArrayList<>(content.get().values());
        int[] slots = GuiLayout.centered(1, Math.min(categories.size(), GuiLayout.capacity(4)));
        for (int i = 0; i < slots.length; i++) {
            MilestoneCategory category = categories.get(i);
            builder.item(slots[i], buildCategoryIcon(profile, category), (clicker, e) -> categoryGui.open(clicker, category.id()));
        }

        guiManager.open(player, builder.build());
    }

    private ItemStack buildCategoryIcon(PackPlayerProfile profile, MilestoneCategory category) {
        long progress = service.progressOf(profile, category.id());
        int claimed = 0;
        int readyToClaim = 0;
        for (int i = 0; i < category.tiers().size(); i++) {
            MilestoneTier tier = category.tiers().get(i);
            MilestoneService.TierState state = service.stateOf(profile, category.id(), i, tier);
            if (state == MilestoneService.TierState.CLAIMED) {
                claimed++;
            } else if (state == MilestoneService.TierState.COMPLETE_UNCLAIMED) {
                readyToClaim++;
            }
        }

        ItemBuilder builder = ItemBuilder.of(category.icon())
                .name(MenuLore.buttonName(MenuLore.ACCENT, category.displayName().toUpperCase(java.util.Locale.ROOT)));
        List<String> data = new ArrayList<>();
        data.add("Progress: &f" + Formatting.format((double) progress));
        data.add("Tiers Claimed: " + MenuLore.progress(claimed, category.tiers().size()));
        if (readyToClaim > 0) {
            data.add("&a" + readyToClaim + " tier(s) ready to claim!");
        }
        MenuLore.button("milestones", List.of(), MenuLore.ACCENT, data, "Click to View")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
