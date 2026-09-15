package me.dontshare.yieldachievements.gui;

import me.dontshare.yieldachievements.store.StoreProduct;
import me.dontshare.yieldachievements.store.StoreProductCategory;
import me.dontshare.yieldachievements.store.StoreService;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
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

/**
 * The Credits store's actual product grid - one instance shared by both the
 * Ranks and Gamepasses {@link StoreHubGui} tabs (see {@code
 * YieldAchievements#onEnable}, which registers one {@link
 * me.dontshare.yieldpacks.store.StoreCategory} per {@link
 * StoreProductCategory}), each showing only that category's own products.
 * Renders directly into the hub's already-open Gui - see {@link #renderInto}
 * - never its own separate screen, so paging/purchasing never opens a
 * second inventory.
 */
public final class StoreGui {

    private static final String ACCENT = "<#FFD700>";
    private static final int PREV_SLOT = 45;
    private static final int BALANCE_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    /** Rows 1-4 of the hub's shared 6-row Gui (slots 9-44, 36 slots) - row 0 belongs to {@link StoreHubGui}'s own category tabs, row 5 (45-53) is this tab's own prev/balance/close/next bar. */
    private static final int PAGE_SIZE = 36;
    private static final int ROW_OFFSET = 9;

    private final Supplier<Map<String, StoreProduct>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final StoreService service;
    /** Keyed by "{playerId}:{category}" - Ranks and Gamepasses page independently of each other. */
    private final Map<String, Integer> pageIndex = new ConcurrentHashMap<>();

    public StoreGui(Supplier<Map<String, StoreProduct>> content, PlayerDataStore<PackPlayerProfile> store, StoreService service) {
        this.content = content;
        this.store = store;
        this.service = service;
    }

    /**
     * Paints every product in {@code category} into the shared hub Gui.
     * Every click refreshes back into the SAME Gui (via {@code
     * hub.refreshContent}) rather than opening a new one.
     */
    public void renderInto(Player player, Gui gui, StoreHubGui hub, StoreProductCategory category) {
        UUID id = player.getUniqueId();
        String pageKey = id + ":" + category;
        PackPlayerProfile profile = store.getOrCreate(id);
        List<StoreProduct> matching = content.get().values().stream().filter(p -> p.category() == category).toList();
        Page<StoreProduct> page = Page.of(matching, pageIndex.getOrDefault(pageKey, 0), PAGE_SIZE);
        pageIndex.put(pageKey, page.index());

        List<StoreProduct> items = page.items();
        for (int i = 0; i < PAGE_SIZE; i++) {
            int slot = ROW_OFFSET + i;
            if (i < items.size()) {
                StoreProduct product = items.get(i);
                gui.set(slot, buildIcon(profile, product), (clicker, e) -> {
                    purchase(clicker, product.id());
                    hub.refreshContent(clicker);
                });
            } else {
                gui.set(slot, GuiIcons.filler(), null);
            }
        }
        gui.set(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()), (clicker, e) -> {
            turnPage(clicker, pageKey, -1);
            hub.refreshContent(clicker);
        });
        gui.set(BALANCE_SLOT, buildBalanceIcon(profile), null);
        gui.set(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
        gui.set(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()), (clicker, e) -> {
            turnPage(clicker, pageKey, 1);
            hub.refreshContent(clicker);
        });
        IntStream.range(45, 54)
                .filter(s -> s != PREV_SLOT && s != BALANCE_SLOT && s != CLOSE_SLOT && s != NEXT_SLOT)
                .forEach(s -> gui.set(s, GuiIcons.filler(), null));
    }

    private void turnPage(Player player, String pageKey, int delta) {
        pageIndex.put(pageKey, pageIndex.getOrDefault(pageKey, 0) + delta);
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
