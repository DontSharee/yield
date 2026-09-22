package me.dontshare.yieldpacks.enchant;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.IntStream;

/**
 * /enchantmarket - this hour's books for this player, what each costs, and
 * how long until everyone's market restocks.
 * <p>
 * The whole row is visible at once and nothing is hidden behind a click:
 * the point of a rotating shop is to glance at it, see whether this is the
 * hour with the Legendary in it, and move on if not.
 */
public final class EnchantMarketGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int ROWS = 4;
    private static final int HEADER_SLOT = 4;
    private static final int BACK_SLOT = 27;
    private static final int CLOSE_SLOT = 31;
    /** Offer positions along the middle row - six skip the centre so the row stays symmetrical, seven fill it. */
    private static final int[] SIX = {10, 11, 12, 14, 15, 16};
    private static final int[] SEVEN = {10, 11, 12, 13, 14, 15, 16};

    private final EnchantMarketService market;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final GuiManager guiManager;
    /** Set after construction - the enchant screen and this one point at each other. */
    private EnchantGui enchantGui;

    public EnchantMarketGui(EnchantMarketService market, PlayerDataStore<PackPlayerProfile> store, GuiManager guiManager) {
        this.market = market;
        this.store = store;
        this.guiManager = guiManager;
    }

    public void setEnchantGui(EnchantGui enchantGui) {
        this.enchantGui = enchantGui;
    }

    public void open(Player player) {
        long hour = EnchantMarketService.currentHour();
        List<EnchantMarketService.Offer> offers = market.offersFor(player.getUniqueId(), hour);
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());

        GuiBuilder builder = Gui.builder(ROWS, "Enchant Market");
        builder.fill(IntStream.range(0, ROWS * 9), GuiIcons.filler());
        int[] positions = offers.size() > SIX.length ? SEVEN : SIX;
        for (int i = 0; i < offers.size() && i < positions.length; i++) {
            EnchantMarketService.Offer offer = offers.get(i);
            boolean bought = market.isBought(profile, hour, offer.index());
            builder.item(positions[i], offerIcon(player, profile, offer, bought),
                    (clicker, event) -> buy(clicker, hour, offer));
        }
        builder.item(HEADER_SLOT, headerIcon(offers));
        if (enchantGui != null) {
            builder.item(BACK_SLOT, backIcon(), (clicker, event) -> enchantGui.open(clicker));
        }
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        guiManager.open(player, builder.build());
    }

    private void buy(Player player, long hour, EnchantMarketService.Offer offer) {
        EnchantMarketService.BuyResult result = market.buy(player, hour, offer.index());
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.1f);
                player.sendMessage(Text.parse("<#4BD9FF>✦</#4BD9FF> <gray>Bought</gray> <book><gray>.</gray> "
                                + "<dark_gray>Drag it into a slot at /enchants.</dark_gray>",
                        Placeholder.component("book", Text.parse(EnchantItem.displayName(offer.type(), offer.rarity())))));
            }
            case TOO_POOR -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<gray>You need <price> coins for that.</gray>",
                        Placeholder.unparsed("price", Formatting.format(market.priceOf(player, offer)))));
            }
            case RESTOCKED -> player.sendMessage(Text.parse(
                    "<#4BD9FF>✦</#4BD9FF> <gray>The market just restocked - here's the new one.</gray>"));
            case SOLD_OUT -> {
                // Already bought - the icon says so, nothing to add.
            }
        }
        open(player);
    }

    private ItemStack offerIcon(Player player, PackPlayerProfile profile, EnchantMarketService.Offer offer, boolean bought) {
        BigInteger price = market.priceOf(player, offer);
        boolean affordable = profile.getCoins().compareTo(price) >= 0;
        ItemBuilder builder = ItemBuilder.of(bought ? Material.GRAY_DYE : offer.type().icon())
                .name(EnchantItem.displayName(offer.type(), offer.rarity()));
        builder.lore("&7Bonus: " + EnchantItem.bonusLine(offer.type(), offer.rarity()));
        builder.lore("");
        builder.lore("&7Price: &e" + Formatting.format(price) + " &7coins");
        builder.lore("");
        if (bought) {
            builder.lore("&8Sold - back at the next restock.");
        } else if (affordable) {
            builder.lore("&8[CLICK] &fTo Buy");
        } else {
            builder.lore("&cYou can't afford this yet.");
        }
        return builder.hideAttributes().build();
    }

    private ItemStack headerIcon(List<EnchantMarketService.Offer> offers) {
        long minutes = Math.max(1, (EnchantMarketService.millisUntilRestock() + 59_999) / 60_000);
        boolean legendary = offers.stream().anyMatch(offer -> offer.rarity().sortOrder() >= EnchantService.LEGENDARY_SORT);
        ItemBuilder builder = ItemBuilder.of(Material.CLOCK).name(MenuLore.infoName(ACCENT, "ENCHANT MARKET"));
        MenuLore.info("market", List.of(
                        " &7Your own books, restocked for",
                        " &7everyone at the top of every",
                        " &7hour. Each can be bought once."),
                ACCENT,
                List.of("Restocks in: &f" + minutes + "m",
                        legendary ? "&6&lA LEGENDARY is in stock!" : "&7No Legendary this hour."))
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack backIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.ENCHANTED_BOOK).name(MenuLore.buttonName(ACCENT, "YOUR ENCHANTS"));
        MenuLore.button("enchants", List.of(" &7Slot the books you own."), ACCENT, "Click to Open")
                .forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
