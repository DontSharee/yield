package me.dontshare.yieldachievements.gui;

import me.dontshare.yieldachievements.store.StoreProduct;
import me.dontshare.yieldachievements.store.StoreService;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.store.StoreHubGui;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/** /buy - the Credits store, ranks/gamepasses purchasable with achievement/milestone earnings. */
public final class StoreGui {

    private static final String ACCENT = "<#FFD700>";
    private static final int TOTAL_ROWS = 6;
    private static final int CONTENT_ROWS = TOTAL_ROWS - 1;
    private static final int PAGE_SIZE = CONTENT_ROWS * 9;
    private static final int PREV_SLOT = 45;
    private static final int BALANCE_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    /**
     * Same shape as the standalone screen, one row shorter (row 0 belongs
     * to {@link StoreHubGui}'s own category tabs) - rows 1-4 hold products,
     * row 5 keeps the exact same prev/balance/close/next layout as {@link
     * #open}'s own bottom bar, just used as this category's content instead
     * of the whole GUI's.
     */
    private static final int HUB_PAGE_SIZE = (CONTENT_ROWS - 1) * 9;
    private static final int HUB_ROW_OFFSET = 9;

    private final Supplier<Map<String, StoreProduct>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final StoreService service;
    private final GuiManager guiManager;
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public StoreGui(Supplier<Map<String, StoreProduct>> content, PlayerDataStore<PackPlayerProfile> store,
                     StoreService service, GuiManager guiManager) {
        this.content = content;
        this.store = store;
        this.service = service;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        UUID id = player.getUniqueId();
        PackPlayerProfile profile = store.getOrCreate(id);
        List<StoreProduct> all = new ArrayList<>(content.get().values());
        Page<StoreProduct> page = Page.of(all, pageIndex.getOrDefault(id, 0), PAGE_SIZE);
        pageIndex.put(id, page.index());

        var builder = Gui.builder(TOTAL_ROWS, "Store");
        List<StoreProduct> items = page.items();
        for (int i = 0; i < items.size(); i++) {
            StoreProduct product = items.get(i);
            builder.item(i, buildIcon(profile, product), (clicker, e) -> purchase(clicker, product.id()));
        }
        builder.fill(IntStream.range(45, 54).filter(s -> s != PREV_SLOT && s != BALANCE_SLOT && s != CLOSE_SLOT && s != NEXT_SLOT), GuiIcons.filler());
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> turnPage(clicker, -1));
        builder.item(BALANCE_SLOT, buildBalanceIcon(profile));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> turnPage(clicker, 1));

        guiManager.open(player, builder.build());
    }

    private void turnPage(Player player, int delta) {
        pageIndex.put(player.getUniqueId(), pageIndex.getOrDefault(player.getUniqueId(), 0) + delta);
        open(player);
    }

    private void purchase(Player player, String productId) {
        StoreService.PurchaseResult result = service.purchase(player, productId);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(Text.parse("<green><bold>Purchased!</bold></green> <gray>Thank you for your support.</gray>"));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            }
            case NOT_ENOUGH_CREDITS -> player.sendMessage(Text.parse("<red>You don't have enough credits for that.</red>"));
            case UNKNOWN_PRODUCT -> player.sendMessage(Text.parse("<red>That product no longer exists.</red>"));
        }
        open(player);
    }

    /**
     * Same content {@link #open} renders, painted into an ALREADY-OPEN
     * {@link StoreHubGui} tab instead of its own dedicated screen - see
     * {@code StoreCategory.CategoryRenderer}. Every click here refreshes
     * back into the SAME shared Gui (via {@code hub.refreshContent}) rather
     * than calling {@link #open}, which would pop a second inventory.
     */
    public void renderInto(Player player, Gui gui, StoreHubGui hub) {
        UUID id = player.getUniqueId();
        PackPlayerProfile profile = store.getOrCreate(id);
        List<StoreProduct> all = new ArrayList<>(content.get().values());
        Page<StoreProduct> page = Page.of(all, pageIndex.getOrDefault(id, 0), HUB_PAGE_SIZE);
        pageIndex.put(id, page.index());

        // Rows 1-4 (slots 9-44, 36 slots = HUB_PAGE_SIZE) hold products -
        // the category row above takes what used to be this screen's own
        // row 0. Row 5 (slots 45-53) is untouched: PREV/BALANCE/CLOSE/NEXT
        // already sit at the same absolute slots either way, since both the
        // standalone screen and this hub tab are 6-row GUIs and row 5 is
        // the last row of both.
        List<StoreProduct> items = page.items();
        for (int i = 0; i < HUB_PAGE_SIZE; i++) {
            int slot = HUB_ROW_OFFSET + i;
            if (i < items.size()) {
                StoreProduct product = items.get(i);
                gui.set(slot, buildIcon(profile, product), (clicker, e) -> {
                    purchaseInHub(clicker, product.id());
                    hub.refreshContent(clicker);
                });
            } else {
                gui.set(slot, GuiIcons.filler(), null);
            }
        }
        gui.set(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> {
            turnPageInHub(clicker, -1);
            hub.refreshContent(clicker);
        });
        gui.set(BALANCE_SLOT, buildBalanceIcon(profile), null);
        gui.set(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        gui.set(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> {
            turnPageInHub(clicker, 1);
            hub.refreshContent(clicker);
        });
        IntStream.range(45, 54)
                .filter(s -> s != PREV_SLOT && s != BALANCE_SLOT && s != CLOSE_SLOT && s != NEXT_SLOT)
                .forEach(s -> gui.set(s, GuiIcons.filler(), null));
    }

    private void turnPageInHub(Player player, int delta) {
        pageIndex.put(player.getUniqueId(), pageIndex.getOrDefault(player.getUniqueId(), 0) + delta);
    }

    private void purchaseInHub(Player player, String productId) {
        StoreService.PurchaseResult result = service.purchase(player, productId);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(Text.parse("<green><bold>Purchased!</bold></green> <gray>Thank you for your support.</gray>"));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
            }
            case NOT_ENOUGH_CREDITS -> player.sendMessage(Text.parse("<red>You don't have enough credits for that.</red>"));
            case UNKNOWN_PRODUCT -> player.sendMessage(Text.parse("<red>That product no longer exists.</red>"));
        }
    }

    private ItemStack buildBalanceIcon(PackPlayerProfile profile) {
        ItemBuilder builder = ItemBuilder.of(Material.SUNFLOWER).name(MenuLore.infoName(ACCENT, "CREDITS"));
        MenuLore.info("wallet", List.of(), ACCENT, List.of("Balance: &f" + Formatting.format(profile.getCredits())))
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildIcon(PackPlayerProfile profile, StoreProduct product) {
        boolean canAfford = profile.getCredits().compareTo(product.cost()) >= 0;
        ItemBuilder builder = ItemBuilder.of(product.icon()).name(MenuLore.buttonName(ACCENT, product.displayName()));
        List<String> description = new ArrayList<>();
        for (String line : product.description()) {
            description.add(" &7" + line);
        }
        List<String> data = List.of("Cost: " + (canAfford ? "&e" : "&c") + Formatting.format(product.cost()) + " &7Credits");
        String callToAction = canAfford ? "Click to Purchase" : "Not Enough Credits";
        MenuLore.purchase("package", description, ACCENT, data, callToAction).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
