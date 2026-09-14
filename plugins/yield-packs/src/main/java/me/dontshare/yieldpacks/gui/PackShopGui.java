package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.data.PackPoolEntry;
import me.dontshare.yieldpacks.roll.PackRollService;
import me.dontshare.yieldpacks.shop.ShopSlot;
import me.dontshare.yieldpacks.shop.ShopStockService;
import me.dontshare.yieldpacks.store.StoreHubGui;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The pack shop screen - renders this player's own rotating stock (see
 * ShopStockService) instead of the full pack catalog. Buying only ever
 * moves packs into storage (see PackStorageGui); opening them is a
 * separate action. Left-click buys 1, right-click buys as many as
 * currency/stock allow - no intermediate dialog, matching the reference
 * "Merchant" GUI style the user pointed to (icon-prefixed stat lines,
 * simple left/right-click actions).
 */
public final class PackShopGui {

    private final ShopStockService stockService;
    private final GuiManager guiManager;
    private final PackRollService rollService;
    private final Supplier<ItemRegistry> itemRegistry;

    public PackShopGui(ShopStockService stockService, GuiManager guiManager, PackRollService rollService,
                        Supplier<ItemRegistry> itemRegistry) {
        this.stockService = stockService;
        this.guiManager = guiManager;
        this.rollService = rollService;
        this.itemRegistry = itemRegistry;
    }

    private static final int TOTAL_ROWS = 6;
    // The exact inner rectangle the reference GUI uses - rows 1-4, columns
    // 1-7 - leaving row 0, row 5, and both edge columns as a plain empty
    // (air) border on every side.
    private static final List<Integer> CONTENT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );

    public void open(Player player) {
        List<ShopSlot> slots = stockService.currentStock(player);

        Duration untilReset = stockService.timeUntilNextReset();
        var builder = Gui.builder(TOTAL_ROWS, "Packs (resets in " + formatDuration(untilReset) + ")");
        int count = Math.min(slots.size(), CONTENT_SLOTS.size());
        for (int i = 0; i < count; i++) {
            ShopSlot shopSlot = slots.get(i);
            int remaining = stockService.remainingStock(player, shopSlot.pack().id());
            builder.item(CONTENT_SLOTS.get(i), buildIcon(shopSlot, remaining), (clicker, event) -> {
                if (remaining <= 0) {
                    clicker.sendMessage(Text.parse("<red>That pack is sold out this cycle.</red>"));
                    return;
                }
                if (event.isRightClick()) {
                    buyMax(clicker, shopSlot.pack());
                } else {
                    buyQuantity(clicker, shopSlot.pack(), 1);
                }
            });
        }
        // No filler anywhere - every slot this loop doesn't touch stays
        // genuinely empty (air), matching the reference GUI's plain,
        // undecorated border instead of a glass-pane frame.
        builder.item(TOTAL_ROWS * 9 - 5, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    /**
     * Same content {@link #open} renders, painted into an ALREADY-OPEN
     * {@link StoreHubGui} tab instead of its own dedicated screen - see
     * {@code StoreCategory.CategoryRenderer}. Every click here refreshes
     * back into the SAME shared Gui (via {@code hub.refreshContent}) rather
     * than calling {@link #open}, which would pop a second inventory.
     */
    public void renderInto(Player player, Gui gui, StoreHubGui hub) {
        // This screen's own CONTENT_SLOTS deliberately leaves a plain
        // border around the product grid (see its own javadoc) - clear the
        // WHOLE shared hub region first, or a wider category (the Credits
        // Store fills every one of StoreHubGui.CONTENT_SLOTS) would leave
        // stale icons behind in that border when a player switches tabs.
        for (int slot : StoreHubGui.CONTENT_SLOTS) {
            gui.set(slot, GuiIcons.filler(), null);
        }
        List<ShopSlot> slots = stockService.currentStock(player);
        int count = Math.min(slots.size(), CONTENT_SLOTS.size());
        for (int i = 0; i < count; i++) {
            ShopSlot shopSlot = slots.get(i);
            int remaining = stockService.remainingStock(player, shopSlot.pack().id());
            gui.set(CONTENT_SLOTS.get(i), buildIcon(shopSlot, remaining), (clicker, event) -> {
                if (remaining <= 0) {
                    clicker.sendMessage(Text.parse("<red>That pack is sold out this cycle.</red>"));
                    return;
                }
                if (event.isRightClick()) {
                    buyMax(clicker, shopSlot.pack(), () -> hub.refreshContent(clicker));
                } else {
                    buyQuantity(clicker, shopSlot.pack(), 1, () -> hub.refreshContent(clicker));
                }
            });
        }
        for (int i = count; i < CONTENT_SLOTS.size(); i++) {
            gui.set(CONTENT_SLOTS.get(i), GuiIcons.filler(), null);
        }
        gui.set(TOTAL_ROWS * 9 - 5, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
    }

    private void buyMax(Player player, PackDefinition pack) {
        buyMax(player, pack, () -> open(player));
    }

    private void buyMax(Player player, PackDefinition pack, Runnable onDone) {
        int max = rollService.maxAffordable(player, pack.id(), 999);
        if (max <= 0) {
            player.sendMessage(Text.parse("<red>You can't afford any of that right now.</red>"));
            return;
        }
        buyQuantity(player, pack, max, onDone);
    }

    private void buyQuantity(Player player, PackDefinition pack, int quantity) {
        buyQuantity(player, pack, quantity, () -> open(player));
    }

    private void buyQuantity(Player player, PackDefinition pack, int quantity, Runnable onDone) {
        PackRollService.PurchaseResult result = rollService.buyIntoStorage(player, pack.id(), quantity);
        if (!result.success()) {
            player.sendMessage(Text.parse("<red><reason></red>", Placeholder.unparsed("reason", result.failureReason())));
            return;
        }
        player.sendMessage(Text.parse(
                "<#4BD9FF><bold>Packs</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Bought <count>x <pack> - check /packs to open.</gray>",
                Placeholder.unparsed("count", String.valueOf(quantity)),
                Placeholder.unparsed("pack", Formatting.stripLeadingColorCodes(pack.displayName()))));
        onDone.run();
    }

    // A plain strikethrough run of spaces draws as a solid horizontal rule
    // in item lore - same trick OreCubeService's HP bar uses, just legacy-
    // coded here to match this GUI's own lore style.
    private static final String SEPARATOR_LINE = "&8&m                              ";

    private ItemStack buildIcon(ShopSlot shopSlot, int remaining) {
        PackDefinition pack = shopSlot.pack();
        boolean soldOut = remaining <= 0;
        ItemBuilder builder = ItemBuilder.of(soldOut ? Material.BARRIER : pack.material())
                .name(pack.displayName());
        if (!soldOut && pack.customModelData() != null) {
            builder.modelData(pack.customModelData());
        }

        List<String> lore = new ArrayList<>();
        lore.add("&8" + Formatting.fancyFont("pack"));
        lore.add("");
        lore.addAll(buildOddsLines(pack));
        lore.add("");
        lore.add("&7Cost: &a$" + Formatting.format(pack.coinCost())
                + (pack.diamondCost() > 0 ? " &8+ &b" + pack.diamondCost() + " diamonds" : ""));
        lore.add("&7Stock: " + (soldOut ? "&c0" : "&a" + remaining + "x"));
        lore.add(SEPARATOR_LINE);
        if (soldOut) {
            lore.add("&c&l✗ Out of Stock!");
        } else {
            lore.add("&8[LEFT-CLICK] &fTo Buy");
            lore.add("&8[RIGHT-CLICK] &fTo Auto Buy");
        }
        builder.lore(lore);
        return builder.hideAttributes().build();
    }

    /** "&lt;item's own colored name&gt; &7(12.3%)" per pool entry, rarest last - the odds disclosure the "Prism Lootbox" reference shows for its own rewards. */
    private List<String> buildOddsLines(PackDefinition pack) {
        double total = pack.pool().stream().mapToDouble(PackPoolEntry::weight).sum();
        if (total <= 0) {
            return List.of();
        }
        ItemRegistry registry = itemRegistry.get();
        return pack.pool().stream()
                .sorted(Comparator.comparingDouble(PackPoolEntry::weight).reversed())
                .flatMap(entry -> registry.find(entry.itemId()).stream()
                        .map(item -> item.displayName() + " &7(" + formatPercent(entry.weight() / total * 100) + "%)"))
                .toList();
    }

    private String formatPercent(double percent) {
        return percent >= 10 ? String.valueOf(Math.round(percent)) : String.format(java.util.Locale.ROOT, "%.1f", percent);
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.toSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }
}
