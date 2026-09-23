package me.dontshare.yieldauctionhouse.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.dontshare.yieldauctionhouse.AuctionService;
import me.dontshare.yieldauctionhouse.data.AuctionConfig;
import me.dontshare.yieldauctionhouse.data.AuctionCurrency;
import me.dontshare.yieldauctionhouse.data.AuctionListing;
import me.dontshare.yieldauctionhouse.store.AuctionListingStore;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.gui.Page;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.item.ItemSerialization;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * The /auction hub - browses every ACTIVE listing off ONE shared,
 * periodically-refreshed snapshot ({@link #refresh()}) rather than each
 * viewer triggering their own database query, so any number of players
 * browsing at once still costs exactly one query per refresh interval
 * ("efficient, lag free, live updating" - the snapshot is what makes sold/
 * new/cancelled listings show up for everyone without a manual reopen).
 * Filtering/paging happen in memory over that shared snapshot. Listing
 * items is command-only ("/ah sell &lt;price&gt; [currency]") - there is no
 * "Sell" button here.
 */
public final class AuctionBrowseGui {

    private static final int TOTAL_ROWS = 6;
    private static final List<Integer> CONTENT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    );
    private static final int PREV_SLOT = 45;
    private static final int CURRENCY_FILTER_SLOT = 47;
    private static final int MY_LISTINGS_SLOT = 49;
    private static final int COLLECTION_BOX_SLOT = 51;
    private static final int NEXT_SLOT = 53;
    private static final String ACCENT = "<#4BD9FF>";

    private enum CurrencyFilter {ALL, COINS, DIAMONDS, CREDITS}

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final AuctionListingStore listingStore;
    private final AuctionService service;
    private final GuiManager guiManager;
    private final Supplier<AuctionConfig> config;
    private final AuctionMyListingsGui myListingsGui;
    private final AuctionCollectionBoxGui collectionBoxGui;

    private volatile List<AuctionListing> snapshot = List.of();
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();
    private final Map<UUID, CurrencyFilter> currencyFilter = new ConcurrentHashMap<>();

    public AuctionBrowseGui(JavaPlugin plugin, DatabaseManager databaseManager, AuctionListingStore listingStore,
                             AuctionService service, GuiManager guiManager, Supplier<AuctionConfig> config,
                             AuctionMyListingsGui myListingsGui, AuctionCollectionBoxGui collectionBoxGui) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.listingStore = listingStore;
        this.service = service;
        this.guiManager = guiManager;
        this.config = config;
        this.myListingsGui = myListingsGui;
        this.collectionBoxGui = collectionBoxGui;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 0L, config.get().browseRefreshIntervalTicks());
    }

    private void refresh() {
        // Nobody is looking, so there is nothing to refresh. Without this the
        // timer pulled and deserialized up to 2000 listings every few seconds
        // for the entire uptime of the server, viewers or not.
        if (viewers.isEmpty()) {
            return;
        }
        databaseManager.supplyAsync(listingStore::findActive).thenAccept(list -> {
            snapshot = list;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (UUID viewerId : List.copyOf(viewers)) {
                    Player viewer = Bukkit.getPlayer(viewerId);
                    if (viewer != null && viewer.isOnline()) {
                        render(viewer);
                    } else {
                        viewers.remove(viewerId);
                    }
                }
            });
        });
    }

    public void open(Player player) {
        viewers.add(player.getUniqueId());
        render(player);
    }

    private void close(Player player) {
        viewers.remove(player.getUniqueId());
    }

    private void render(Player player) {
        UUID id = player.getUniqueId();
        List<AuctionListing> filtered = snapshot.stream()
                .filter(l -> matchesCurrency(l, currencyFilter.getOrDefault(id, CurrencyFilter.ALL)))
                .toList();
        Page<AuctionListing> page = Page.of(filtered, pageIndex.getOrDefault(id, 0), CONTENT_SLOTS.size());
        pageIndex.put(id, page.index());

        var builder = Gui.builder(TOTAL_ROWS, "Auction House");
        builder.fill(IntStream.range(0, 45), GuiIcons.filler());
        List<AuctionListing> items = page.items();
        int[] contentSlots = GuiLayout.centered(1, page.items().size());
        for (int i = 0; i < items.size(); i++) {
            AuctionListing listing = items.get(i);
            ItemStack item = ItemSerialization.deserialize(listing.serializedItem());
            builder.item(contentSlots[i], buildIcon(listing, item), (clicker, event) -> confirmPurchase(clicker, listing, item));
        }

        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page.hasPrevious()),
                (clicker, event) -> {
                    pageIndex.put(id, page.index() - 1);
                    render(clicker);
                });
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page.hasNext()),
                (clicker, event) -> {
                    pageIndex.put(id, page.index() + 1);
                    render(clicker);
                });
        builder.item(CURRENCY_FILTER_SLOT, buildCurrencyFilterIcon(player), (clicker, event) -> {
            cycleCurrency(id);
            pageIndex.put(id, 0);
            render(clicker);
        });
        builder.item(MY_LISTINGS_SLOT, buildNavIcon(Material.PAPER, "MY LISTINGS", "View/cancel your own listings"),
                (clicker, event) -> myListingsGui.open(clicker));
        builder.item(COLLECTION_BOX_SLOT, buildNavIcon(Material.CHEST, "COLLECTION BOX", "Claim sold/returned/bought items"),
                (clicker, event) -> collectionBoxGui.open(clicker));

        Gui gui = builder.build();
        gui.setCloseHandler(this::close);
        guiManager.open(player, gui);
    }

    private boolean matchesCurrency(AuctionListing listing, CurrencyFilter filter) {
        return filter == CurrencyFilter.ALL || listing.currency().name().equals(filter.name());
    }

    private void cycleCurrency(UUID id) {
        CurrencyFilter current = currencyFilter.getOrDefault(id, CurrencyFilter.ALL);
        CurrencyFilter[] values = CurrencyFilter.values();
        currencyFilter.put(id, values[(current.ordinal() + 1) % values.length]);
    }

    private void confirmPurchase(Player player, AuctionListing listing, ItemStack item) {
        if (listing.sellerId().equals(player.getUniqueId())) {
            player.sendMessage(Text.parse("<red>You can't buy your own listing.</red>"));
            return;
        }
        String currencySymbol = currencyLabel(listing.currency());
        String itemName = item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName())
                : (item != null ? item.getType().name() : "Unknown Item");
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.parse("Confirm Purchase"))
                        .body(List.of(DialogBody.plainMessage(Text.parse(
                                "&7Buy &f" + itemName + " &7for &a" + Formatting.format(listing.price()) + " " + currencySymbol + "&7?"))))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Text.parse("Buy"))
                                .action(DialogAction.customClick((view, audience) -> doPurchase(player, listing), ClickCallback.Options.builder().build()))
                                .build(),
                        ActionButton.builder(Text.parse("Cancel")).build())));
        player.showDialog(dialog);
    }

    private void doPurchase(Player player, AuctionListing listing) {
        if (service.isBusy(player)) {
            player.sendMessage(Text.parse("<red>Hold on, your last action is still processing.</red>"));
            return;
        }
        service.purchase(player, listing).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            switch (result) {
                case SUCCESS -> player.sendMessage(Text.parse("<green><bold>Purchased!</bold></green> <gray>Check your Collection Box.</gray>"));
                case NO_LONGER_AVAILABLE -> player.sendMessage(Text.parse("<red>That listing is no longer available.</red>"));
                case INSUFFICIENT_FUNDS -> player.sendMessage(Text.parse("<red>You can't afford that.</red>"));
                case OWN_LISTING -> player.sendMessage(Text.parse("<red>You can't buy your own listing.</red>"));
                case BUSY -> player.sendMessage(Text.parse("<red>Hold on, your last action is still processing.</red>"));
                case FAILED -> player.sendMessage(Text.parse("<red>Something went wrong - try again.</red>"));
            }
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Gui) {
                render(player);
            }
        }));
    }

    private ItemStack buildIcon(AuctionListing listing, ItemStack original) {
        String currencySymbol = currencyLabel(listing.currency());
        List<String> data = List.of(
                "Seller: &f" + listing.sellerName(),
                "Price: &a" + Formatting.format(listing.price()) + " " + currencySymbol);
        List<String> lore = MenuLore.button("listing", List.of(), ACCENT, data, "Click to Buy");
        return AuctionPreviewIcon.build(original, lore);
    }

    private String currencyLabel(AuctionCurrency currency) {
        return switch (currency) {
            case COINS -> "&6coins";
            case DIAMONDS -> "&bdiamonds";
            case CREDITS -> "&dcredits";
        };
    }

    private ItemStack buildCurrencyFilterIcon(Player player) {
        CurrencyFilter current = currencyFilter.getOrDefault(player.getUniqueId(), CurrencyFilter.ALL);
        ItemBuilder builder = ItemBuilder.of(Material.SUNFLOWER).name(MenuLore.buttonName(ACCENT, "CURRENCY: " + current.name()));
        MenuLore.button("filter", List.of(" &7Cycle which currency", " &7listings are shown."), ACCENT, "Click to Cycle").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildNavIcon(Material material, String name, String description) {
        ItemBuilder builder = ItemBuilder.of(material).name(MenuLore.buttonName(ACCENT, name));
        MenuLore.button("navigation", List.of(" &7" + description + "."), ACCENT, "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

}
