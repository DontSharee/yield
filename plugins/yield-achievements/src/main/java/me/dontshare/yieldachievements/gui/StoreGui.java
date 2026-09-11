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
