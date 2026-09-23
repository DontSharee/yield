package me.dontshare.yieldmining.orebag;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldmining.orebag.OreBagService.BagEntryView;
import me.dontshare.yieldmining.data.MiningProfile;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/** "/orebag" - separate storage for Special Ore, so it never gets mixed into or lost from the normal inventory. Starts empty for every player - only ever populated by MiningService routing a Special Ore drop through OreBagService. */
public final class OreBagGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int TOTAL_ROWS = 6;
    private static final int CONTENT_ROWS = TOTAL_ROWS - 1;
    /** Centred rows of seven inside the border - see GuiLayout. */
    private static final int PAGE_SIZE = GuiLayout.capacity(CONTENT_ROWS);
    private static final int PREV_SLOT = 45;
    private static final int TOGGLE_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final PlayerDataStore<MiningProfile> miningStore;
    private final OreBagService service;
    private final GuiManager guiManager;
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public OreBagGui(PlayerDataStore<PackPlayerProfile> store, PlayerDataStore<MiningProfile> miningStore,
                      OreBagService service, GuiManager guiManager) {
        this.store = store;
        this.miningStore = miningStore;
        this.service = service;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        UUID id = player.getUniqueId();
        PackPlayerProfile profile = store.getOrCreate(id);
        List<BagEntryView> all = service.entries(profile);
        Page<BagEntryView> page = Page.of(all, pageIndex.getOrDefault(id, 0), PAGE_SIZE);
        pageIndex.put(id, page.index());

        var builder = Gui.builder(TOTAL_ROWS, "Ore Bag");
        List<BagEntryView> items = page.items();
        builder.fill(IntStream.range(0, 45), GuiIcons.filler());
        int[] contentSlots = GuiLayout.centered(0, items.size());
        for (int i = 0; i < items.size(); i++) {
            BagEntryView entry = items.get(i);
            builder.item(contentSlots[i], buildEntryIcon(entry), (clicker, e) -> withdraw(clicker, entry.entryId()));
        }
        builder.fill(IntStream.range(45, 54).filter(s -> s != PREV_SLOT && s != TOGGLE_SLOT && s != CLOSE_SLOT && s != NEXT_SLOT), GuiIcons.filler());
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> turnPage(clicker, -1));
        builder.item(TOGGLE_SLOT, buildToggleIcon(miningStore.getOrCreate(player.getUniqueId()).isOreBagNotificationsEnabled()),
                (clicker, e) -> toggleNotifications(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> turnPage(clicker, 1));

        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, int delta) {
        pageIndex.put(player.getUniqueId(), pageIndex.getOrDefault(player.getUniqueId(), 0) + delta);
        open(player);
    }

    private void withdraw(Player player, String entryId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (service.withdraw(profile, player, entryId)) {
            player.playSound(player.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.7f, 1.3f);
        }
        open(player);
    }

    private void toggleNotifications(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        MiningProfile mining = miningStore.getOrCreate(player.getUniqueId());
        mining.setOreBagNotificationsEnabled(!mining.isOreBagNotificationsEnabled());
        miningStore.save(player.getUniqueId());
        store.save(player.getUniqueId());
        open(player);
    }

    private ItemStack buildEntryIcon(BagEntryView entry) {
        return service.displayItemFor(entry);
    }

    private ItemStack buildToggleIcon(boolean on) {
        ItemBuilder builder = ItemBuilder.of(on ? Material.BELL : Material.GRAY_DYE)
                .name(MenuLore.buttonName(ACCENT, "MESSAGE NOTIFICATIONS"));
        MenuLore.button("settings",
                List.of(" &7Toggle a chat message every",
                        " &7time a Special Ore is added",
                        " &7to your Ore Bag."),
                ACCENT,
                List.of("Status: " + (on ? "&aON" : "&cOFF")),
                "Click to Toggle"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
