package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.roll.PackOpenService;
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
import java.util.List;

/**
 * The merchant screen - this player's own rotating stock of eggs (see
 * ShopStockService) rather than the full catalog. Left-click hatches one,
 * right-click hatches as many as currency and stock allow, and both happen
 * on the spot: there is no storage to buy into any more, so a merchant egg
 * is hatched the same moment it is paid for, exactly like one at a station.
 * Stock is still per-cycle, which is what keeps the merchant a treat rather
 * than a substitute for the zone's own station.
 */
public final class PackShopGui {

    private final ShopStockService stockService;
    private final GuiManager guiManager;
    private final PackRollService rollService;
    private final PackOddsLore oddsLore;
    private final PackOpenService openService;

    public PackShopGui(ShopStockService stockService, GuiManager guiManager, PackRollService rollService,
                        PackOddsLore oddsLore, PackOpenService openService) {
        this.stockService = stockService;
        this.guiManager = guiManager;
        this.rollService = rollService;
        this.oddsLore = oddsLore;
        this.openService = openService;
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
        var builder = Gui.builder(TOTAL_ROWS, "Merchant (resets in " + formatDuration(untilReset) + ")");
        int count = Math.min(slots.size(), CONTENT_SLOTS.size());
        for (int i = 0; i < count; i++) {
            ShopSlot shopSlot = slots.get(i);
            int remaining = stockService.remainingStock(player, shopSlot.pack().id());
            builder.item(CONTENT_SLOTS.get(i), buildIcon(shopSlot, remaining, player), (clicker, event) -> {
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
            gui.set(CONTENT_SLOTS.get(i), buildIcon(shopSlot, remaining, player), (clicker, event) -> {
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
        for (int i = count; i < CONTENT_SLOTS.size(); i++) {
            gui.set(CONTENT_SLOTS.get(i), GuiIcons.filler(), null);
        }
        gui.set(TOTAL_ROWS * 9 - 5, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
    }

    private void buyMax(Player player, PackDefinition pack) {
        int max = rollService.maxAffordable(player, pack.id(), openService.maxTierFor(player));
        if (max <= 0) {
            player.sendMessage(Text.parse("<red>You can't afford any of that right now.</red>"));
            return;
        }
        buyQuantity(player, pack, max);
    }

    /**
     * Never re-opens the shop afterwards, unlike the buy-into-storage flow
     * it replaced: the eggs are cracking open in the world right now, and
     * putting a chest screen back over them is the one thing that would
     * waste the reveal.
     */
    private void buyQuantity(Player player, PackDefinition pack, int quantity) {
        int remaining = stockService.remainingStock(player, pack.id());
        if (remaining < quantity) {
            player.sendMessage(Text.parse("<red>Not enough left in stock this cycle.</red>"));
            return;
        }
        // The hatch plays out in the world - the shop screen has to be out
        // of the way for the player to see their own eggs crack.
        player.closeInventory();
        PackRollService.PurchaseResult result = openService.tryHatch(player, pack.id(), quantity);
        if (!result.success()) {
            if (result.failureReason() != null) {
                player.sendMessage(Text.parse("<red><reason></red>", Placeholder.unparsed("reason", result.failureReason())));
            }
            return;
        }
        // Only now, once the eggs are genuinely hatched and paid for -
        // recording the stock first would burn a cycle's supply on a hatch
        // the cooldown had already refused.
        stockService.recordPurchase(player, pack.id(), result.rolls().size());
    }

    // A plain strikethrough run of spaces draws as a solid horizontal rule
    // in item lore - same trick OreCubeService's HP bar uses, just legacy-
    // coded here to match this GUI's own lore style.
    private static final String ACCENT = MenuLore.ACCENT;

    private ItemStack buildIcon(ShopSlot shopSlot, int remaining, Player viewer) {
        PackDefinition pack = shopSlot.pack();
        boolean soldOut = remaining <= 0;
        ItemBuilder builder = ItemBuilder.of(soldOut ? Material.BARRIER : pack.material())
                .name(pack.displayName());
        if (!soldOut && pack.customModelData() != null) {
            builder.modelData(pack.customModelData());
        }

        List<String> data = new ArrayList<>(oddsLore.lines(pack, viewer));
        data.add("");
        data.add("Cost: " + PackOddsLore.costLine(pack, 1));
        data.add("Stock: " + (soldOut ? "&c0" : "&a" + remaining + "x"));
        if (soldOut) {
            MenuLore.info("egg", List.of("&c&l\u2717 Out of Stock!"), ACCENT, data).forEach(builder::lore);
        } else {
            MenuLore.dualAction("egg", List.of(), ACCENT, data, "Left Click to hatch 1", "Right Click to hatch max")
                    .forEach(builder::lore);
        }
        return builder.hideAttributes().build();
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.toSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }
}
