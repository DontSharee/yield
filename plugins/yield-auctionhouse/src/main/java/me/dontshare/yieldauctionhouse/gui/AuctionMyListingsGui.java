package me.dontshare.yieldauctionhouse.gui;

import me.dontshare.yieldauctionhouse.AuctionService;
import me.dontshare.yieldauctionhouse.data.AuctionCurrency;
import me.dontshare.yieldauctionhouse.data.AuctionListing;
import me.dontshare.yieldauctionhouse.store.AuctionListingStore;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.item.ItemSerialization;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.GuiIcons;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.IntStream;

/** The seller's own active listings, with a Cancel button per entry - queried live per-open (a personal, low-volume view, unlike the shared browse snapshot). */
public final class AuctionMyListingsGui {

    private static final int TOTAL_ROWS = 6;
    private static final int CONTENT_START = 0;
    private static final int CONTENT_END = 45;
    private static final int BACK_SLOT = 45;
    private static final String ACCENT = "<#4BD9FF>";

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final AuctionListingStore listingStore;
    private final AuctionService service;
    private final GuiManager guiManager;
    private AuctionBrowseGui browseGui;

    public AuctionMyListingsGui(JavaPlugin plugin, DatabaseManager databaseManager, AuctionListingStore listingStore,
                                 AuctionService service, GuiManager guiManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.listingStore = listingStore;
        this.service = service;
        this.guiManager = guiManager;
    }

    public void setBrowseGui(AuctionBrowseGui browseGui) {
        this.browseGui = browseGui;
    }

    public void open(Player player) {
        databaseManager.supplyAsync(() -> listingStore.findActiveBy(player.getUniqueId()))
                .thenAccept(listings -> Bukkit.getScheduler().runTask(plugin, () -> render(player, listings)));
    }

    private void render(Player player, List<AuctionListing> listings) {
        var builder = Gui.builder(TOTAL_ROWS, "My Listings");
        builder.fill(IntStream.range(CONTENT_END, TOTAL_ROWS * 9).filter(s -> s != BACK_SLOT), GuiIcons.filler());

        int slot = CONTENT_START;
        for (AuctionListing listing : listings) {
            if (slot >= CONTENT_END) {
                break;
            }
            builder.item(slot, buildIcon(listing), (clicker, event) -> cancel(clicker, listing));
            slot++;
        }

        builder.item(BACK_SLOT, buildBackButton(), (clicker, event) -> {
            if (browseGui != null) {
                browseGui.open(clicker);
            }
        });

        guiManager.open(player, builder.build());
    }

    private void cancel(Player player, AuctionListing listing) {
        if (service.isBusy(player)) {
            player.sendMessage(Text.parse("<red>Hold on, your last action is still processing.</red>"));
            return;
        }
        service.cancel(player, listing.id()).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            switch (result) {
                case SUCCESS -> player.sendMessage(Text.parse("<green>Listing cancelled - check your Collection Box.</green>"));
                case NOT_FOUND_OR_NOT_YOURS -> player.sendMessage(Text.parse("<red>That listing already sold or was already cancelled.</red>"));
                case BUSY -> player.sendMessage(Text.parse("<red>Hold on, your last action is still processing.</red>"));
                case FAILED -> player.sendMessage(Text.parse("<red>Something went wrong - try again.</red>"));
            }
            open(player);
        }));
    }

    private ItemStack buildIcon(AuctionListing listing) {
        ItemStack original = ItemSerialization.deserialize(listing.serializedItem());
        String currencySymbol = currencyLabel(listing.currency());
        List<String> lore = MenuLore.button("listing", List.of(), ACCENT,
                List.of("Price: &a" + Formatting.format(listing.price()) + " " + currencySymbol),
                "Click to Cancel");
        return AuctionPreviewIcon.build(original, lore);
    }

    private String currencyLabel(AuctionCurrency currency) {
        return switch (currency) {
            case COINS -> "&6coins";
            case GEMS -> "&bgems";
            case CREDITS -> "&dcredits";
        };
    }

    private ItemStack buildBackButton() {
        ItemBuilder builder = ItemBuilder.of(Material.ARROW).name(MenuLore.buttonName(ACCENT, "BACK"));
        MenuLore.button("navigation", List.of(" &7Return to the Auction House."), ACCENT, "Click to Go Back").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
