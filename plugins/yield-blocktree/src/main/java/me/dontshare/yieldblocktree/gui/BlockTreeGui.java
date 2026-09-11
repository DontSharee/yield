package me.dontshare.yieldblocktree.gui;

import me.dontshare.yieldblocktree.BlockTreeService;
import me.dontshare.yieldblocktree.data.BlockTreeDefinition;
import me.dontshare.yieldblocktree.data.BlockTreeTier;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The /blocktree hub - one icon per configured block (fully data-driven,
 * see blocktree.yml - adding a block is a config change only). Click a
 * block to open its own 7-tier ladder ({@link BlockTreeCategoryGui}).
 */
public final class BlockTreeGui {

    private static final int TOTAL_ROWS = 6;
    private static final int CLOSE_SLOT = 49;
    private static final int BLOCK_START_SLOT = 10;

    private final Supplier<Map<Material, BlockTreeDefinition>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final BlockTreeService service;
    private final GuiManager guiManager;
    private final BlockTreeCategoryGui categoryGui;

    public BlockTreeGui(Supplier<Map<Material, BlockTreeDefinition>> content, PlayerDataStore<PackPlayerProfile> store,
                         BlockTreeService service, GuiManager guiManager, BlockTreeCategoryGui categoryGui) {
        this.content = content;
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
        this.categoryGui = categoryGui;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        var builder = Gui.builder(TOTAL_ROWS, "Blocktree");
        builder.fill(IntStream.range(45, 54).filter(s -> s != CLOSE_SLOT), GuiIcons.filler());
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());

        int slot = BLOCK_START_SLOT;
        for (Map.Entry<Material, BlockTreeDefinition> entry : content.get().entrySet()) {
            if (slot >= 45) {
                break;
            }
            Material material = entry.getKey();
            builder.item(slot, buildBlockIcon(profile, material, entry.getValue()), (clicker, e) -> categoryGui.open(clicker, material));
            slot++;
        }

        guiManager.open(player, builder.build());
    }

    private ItemStack buildBlockIcon(PackPlayerProfile profile, Material material, BlockTreeDefinition def) {
        long progress = service.progressOf(profile, material);
        List<BlockTreeTier> tiers = def.tiers();
        int claimed = 0;
        int readyToClaim = 0;
        for (int i = 0; i < tiers.size(); i++) {
            BlockTreeService.TierState state = service.stateOf(profile, material, i, tiers.get(i));
            if (state == BlockTreeService.TierState.CLAIMED) {
                claimed++;
            } else if (state == BlockTreeService.TierState.COMPLETE_UNCLAIMED) {
                readyToClaim++;
            }
        }

        String plainName = Formatting.stripLeadingColorCodes(def.displayName());
        ItemBuilder builder = ItemBuilder.of(def.icon())
                .name(MenuLore.buttonName(MenuLore.ACCENT, plainName.toUpperCase(Locale.ROOT)));
        List<String> data = new ArrayList<>();
        data.add("Broken: &f" + Formatting.format((double) progress));
        data.add("Tiers Claimed: &f" + claimed + " &7/ &f" + tiers.size());
        if (readyToClaim > 0) {
            data.add("&a" + readyToClaim + " tier(s) ready to claim!");
        }
        MenuLore.button("blocktree", List.of(), MenuLore.ACCENT, data, "Click to View")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
