package me.dontshare.yieldblocktree.gui;

import me.dontshare.yieldblocktree.BlockTreeService;
import me.dontshare.yieldblocktree.data.BlockTreeDefinition;
import me.dontshare.yieldblocktree.data.BlockTreeTier;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The /blocktree hub - one icon per configured block, in blocktree.yml's
 * order (which follows the zone ladder), laid out in centred rows of
 * seven with a border all the way round, and pages past 28.
 * Click a block to open its own ladder ({@link BlockTreeCategoryGui}).
 */
public final class BlockTreeGui {

    private static final int TOTAL_ROWS = 6;
    /** Four centred rows of seven inside the border - see GuiLayout. */
    private static final int PAGE_SIZE = GuiLayout.capacity(4);
    private static final int PREV_SLOT = 48;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 50;

    private final Supplier<Map<Material, BlockTreeDefinition>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final BlockTreeService service;
    private final GuiManager guiManager;
    private final BlockTreeCategoryGui categoryGui;
    /** The page each player last looked at, so Back from a block returns to the same page. */
    private final Map<java.util.UUID, Integer> lastPage = new java.util.concurrent.ConcurrentHashMap<>();

    public BlockTreeGui(Supplier<Map<Material, BlockTreeDefinition>> content, PlayerDataStore<PackPlayerProfile> store,
                         BlockTreeService service, GuiManager guiManager, BlockTreeCategoryGui categoryGui) {
        this.content = content;
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
        this.categoryGui = categoryGui;
    }

    public void open(Player player) {
        open(player, lastPage.getOrDefault(player.getUniqueId(), 0));
    }

    public void open(Player player, int requestedPage) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<Map.Entry<Material, BlockTreeDefinition>> entries = new ArrayList<>(content.get().entrySet());
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        lastPage.put(player.getUniqueId(), page);

        var builder = Gui.builder(TOTAL_ROWS, pages > 1 ? "Blocktree (" + (page + 1) + "/" + pages + ")" : "Blocktree");
        builder.fill(GuiLayout.all(TOTAL_ROWS), GuiIcons.filler());

        int from = page * PAGE_SIZE;
        int shown = Math.min(PAGE_SIZE, entries.size() - from);
        int[] slots = GuiLayout.centered(1, shown);
        for (int i = 0; i < shown; i++) {
            Map.Entry<Material, BlockTreeDefinition> entry = entries.get(from + i);
            Material material = entry.getKey();
            builder.item(slots[i], buildBlockIcon(profile, material, entry.getValue()), (clicker, e) -> categoryGui.open(clicker, material));
        }

        if (page > 0) {
            builder.item(PREV_SLOT, GuiIcons.pageArrow(false, true), (clicker, e) -> open(clicker, page - 1));
        }
        if (page < pages - 1) {
            builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, true), (clicker, e) -> open(clicker, page + 1));
        }
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
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
        data.add("Tiers: " + MenuLore.progress(claimed, tiers.size()));
        if (def.perkTitle() != null) {
            data.add("Top Reward: " + def.perkTitle());
        }
        if (readyToClaim > 0) {
            data.add("");
            data.add("&a" + readyToClaim + " tier" + (readyToClaim == 1 ? "" : "s") + " ready to claim!");
        }
        MenuLore.button("blocktree", List.of(), MenuLore.ACCENT, data, "Click to View")
                .forEach(builder::lore);
        if (readyToClaim > 0 || claimed == tiers.size()) {
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }
}
