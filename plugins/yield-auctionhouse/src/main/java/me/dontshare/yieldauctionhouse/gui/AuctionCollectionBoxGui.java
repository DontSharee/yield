package me.dontshare.yieldauctionhouse.gui;

import me.dontshare.yieldauctionhouse.AuctionService;
import me.dontshare.yieldauctionhouse.data.AuctionClaim;
import me.dontshare.yieldauctionhouse.data.AuctionCurrency;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.item.ItemSerialization;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldpacks.gui.GuiIcons;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Every pending delivery for this player - see {@code AuctionService}'s own
 * Javadoc for why absolutely everything (sale proceeds, a returned listing,
 * a bought item) is delivered here rather than directly.
 */
public final class AuctionCollectionBoxGui {

    private static final int TOTAL_ROWS = 6;
    private static final int CONTENT_START = 0;
    private static final int CONTENT_END = 44;
    private static final int CLAIM_ALL_SLOT = 44;
    private static final int BACK_SLOT = 45;
    private static final String ACCENT = "<#4BD9FF>";

    private final JavaPlugin plugin;
    private final AuctionService service;
    private final GuiManager guiManager;
    private AuctionBrowseGui browseGui;

    public AuctionCollectionBoxGui(JavaPlugin plugin, AuctionService service, GuiManager guiManager) {
        this.plugin = plugin;
        this.service = service;
        this.guiManager = guiManager;
    }

    public void setBrowseGui(AuctionBrowseGui browseGui) {
        this.browseGui = browseGui;
    }

    public void open(Player player) {
        List<AuctionClaim> claims = service.pendingClaimsFor(player);
        var builder = Gui.builder(TOTAL_ROWS, "Collection Box (" + claims.size() + ")");
        builder.fill(IntStream.range(CONTENT_END, TOTAL_ROWS * 9).filter(s -> s != BACK_SLOT && s != CLAIM_ALL_SLOT), GuiIcons.filler());

        int slot = CONTENT_START;
        for (AuctionClaim claim : claims) {
            if (slot >= CONTENT_END) {
                break;
            }
            builder.item(slot, buildIcon(claim), (clicker, event) -> claimOne(clicker, claim));
            slot++;
        }

        builder.item(CLAIM_ALL_SLOT, buildClaimAllButton(), (clicker, event) -> claimAll(clicker));
        builder.item(BACK_SLOT, buildBackButton(), (clicker, event) -> {
            if (browseGui != null) {
                browseGui.open(clicker);
            }
        });

        guiManager.open(player, builder.build());
    }

    private void claimOne(Player player, AuctionClaim claim) {
        service.claim(player, claim.id()).thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> {
            player.sendMessage(success
                    ? Text.parse("<green>Claimed!</green>")
                    : Text.parse("<red>That was already claimed.</red>"));
            open(player);
        }));
    }

    private void claimAll(Player player) {
        service.claimAll(player).thenAccept(count -> Bukkit.getScheduler().runTask(plugin, () -> {
            player.sendMessage(Text.parse("<green>Claimed <count> item(s).</green>",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("count", String.valueOf(count))));
            open(player);
        }));
    }

    private ItemStack buildIcon(AuctionClaim claim) {
        String reasonLabel = switch (claim.reason()) {
            case SALE_PROCEEDS -> "Sold listing";
            case LISTING_RETURNED -> "Returned listing";
            case PURCHASE -> "Your purchase";
        };
        List<String> lore = MenuLore.button("claim", List.of(), ACCENT, List.of("Reason: &f" + reasonLabel), "Click to Claim");
        if (claim.isCurrency()) {
            Material material = switch (claim.currency()) {
                case COINS -> Material.SUNFLOWER;
                case DIAMONDS -> Material.DIAMOND;
                case CREDITS -> Material.AMETHYST_SHARD;
            };
            String currencySymbol = switch (claim.currency()) {
                case COINS -> "&6coins";
                case DIAMONDS -> "&bdiamonds";
                case CREDITS -> "&dcredits";
            };
            ItemBuilder builder = ItemBuilder.of(material)
                    .name(MenuLore.buttonName(ACCENT, "+" + Formatting.format(claim.amount()) + " " + currencySymbol));
            lore.forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        ItemStack original = ItemSerialization.deserialize(claim.serializedItem());
        return AuctionPreviewIcon.build(original, lore);
    }

    private ItemStack buildClaimAllButton() {
        ItemBuilder builder = ItemBuilder.of(Material.HOPPER).name(MenuLore.buttonName("<green>", "CLAIM ALL"));
        MenuLore.button("claim", List.of(" &7Claims every pending", " &7delivery at once."), "<green>", "Click to Claim All").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildBackButton() {
        ItemBuilder builder = ItemBuilder.of(Material.ARROW).name(MenuLore.buttonName(ACCENT, "BACK"));
        MenuLore.button("navigation", List.of(" &7Return to the Auction House."), ACCENT, "Click to Go Back").forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
