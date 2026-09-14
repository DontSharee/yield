package me.dontshare.yieldpacks.store;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.GuiIcons;
import me.dontshare.yieldpacks.gui.PackShopGui;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.rank.RankService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.List;

/**
 * The two {@link StoreCategory} entries yield-packs contributes to its own
 * {@link StoreHubGui} - kept out of {@code YieldPacks}' own already-large
 * onEnable. Every other category (the Credits Store, Crates, ...) is a
 * satellite plugin's own concern, built and registered from that plugin's
 * own onEnable instead.
 */
public final class DefaultStoreCategories {

    private static final String RANKUP_ACCENT = "<#B15CFF>";
    private static final String PACK_SHOP_ACCENT = "<#4BD9FF>";
    private static final int RANKUP_HEADER_SLOT = 22;
    private static final int RANKUP_BUY_SLOT = 30;
    private static final int RANKUP_MAX_SLOT = 32;
    private static final int RANKUP_CLOSE_SLOT = 49;

    private DefaultStoreCategories() {
    }

    public static StoreCategory rankup(PlayerDataStore<PackPlayerProfile> store, RankService rankService) {
        return new StoreCategory("rankup", 0, DefaultStoreCategories::buttonIcon,
                (player, gui, hub) -> renderRankup(player, gui, hub, store, rankService));
    }

    public static StoreCategory packShop(PackShopGui packShopGui) {
        return new StoreCategory("pack_shop", 30, DefaultStoreCategories::packShopButtonIcon, packShopGui::renderInto);
    }

    private static ItemStack buttonIcon(boolean selected) {
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR).name(MenuLore.buttonName(RANKUP_ACCENT, "RANKUP"));
        MenuLore.button("store", List.of(" &7Spend diamonds on a permanent", " &7Rank, boosting diamond income."),
                RANKUP_ACCENT, selected ? "Selected" : "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private static ItemStack packShopButtonIcon(boolean selected) {
        ItemBuilder builder = ItemBuilder.of(Material.CHEST_MINECART).name(MenuLore.buttonName(PACK_SHOP_ACCENT, "PACK SHOP"));
        MenuLore.button("store", List.of(" &7Buy Packs with coins and", " &7diamonds - the source of every pet."),
                PACK_SHOP_ACCENT, selected ? "Selected" : "Click to Open").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /**
     * Rankup is an infinite, paginated ladder (see {@code RankupGui}) - it
     * doesn't fit this hub's flat single-screen tabs the way Store/Crates/
     * the Pack Shop do, so this tab shows a compact "current rank, buy the
     * next one, or max out" panel instead of the full ladder. The complete,
     * page-by-page ladder is still reachable directly via /ranks or
     * /rankup, outside the hub.
     */
    private static void renderRankup(Player player, Gui gui, StoreHubGui hub,
                                      PlayerDataStore<PackPlayerProfile> store, RankService rankService) {
        for (int slot : StoreHubGui.CONTENT_SLOTS) {
            gui.set(slot, GuiIcons.filler(), null);
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());

        ItemBuilder header = ItemBuilder.of(Material.NETHERITE_INGOT)
                .name(RANKUP_ACCENT + "&lRank " + Formatting.toRoman(profile.getRank()));
        MenuLore.info("rank", List.of(), RANKUP_ACCENT, List.of(
                "&7Diamond Multiplier: &b" + Formatting.format(rankService.diamondMultiplier(profile)) + "x",
                "&7Your Diamonds: &b" + Formatting.format(profile.getDiamonds()),
                "&7For the full ladder: &f/ranks"
        )).forEach(header::lore);
        gui.set(RANKUP_HEADER_SLOT, header.hideAttributes().build(), null);

        BigInteger nextCost = rankService.costForRank(profile.getRank());
        boolean canAffordOne = profile.getDiamonds().compareTo(nextCost) >= 0;
        ItemBuilder buyOne = ItemBuilder.of(canAffordOne ? Material.LIME_DYE : Material.RED_STAINED_GLASS_PANE)
                .name((canAffordOne ? "&a&l" : "&c&l") + "Rank " + Formatting.toRoman(profile.getRank() + 1));
        MenuLore.button("rank", List.of(" &7Buy the next single rank."), RANKUP_ACCENT,
                List.of("Cost: &b" + Formatting.format(nextCost) + " diamonds"),
                canAffordOne ? "Click to Buy" : "Not Enough Diamonds").forEach(buyOne::lore);
        gui.set(RANKUP_BUY_SLOT, buyOne.hideAttributes().build(), (clicker, e) -> {
            RankService.RankPreview result = rankService.rankUpOnce(clicker);
            if (result.available() <= 0) {
                clicker.sendMessage(Text.parse("<red>You can't afford that yet.</red>"));
                clicker.playSound(clicker.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            } else {
                announce(clicker, store, result);
            }
            hub.refreshContent(clicker);
        });

        RankService.RankPreview maxPreview = rankService.preview(profile);
        ItemBuilder maxRank = ItemBuilder.of(Material.NETHERITE_INGOT).name(MenuLore.buttonName("<gold>", "MAX RANK"));
        MenuLore.button("rank", List.of(" &7Buys every rank you can", " &7currently afford at once.",
                        " &7Available now: &e" + maxPreview.available()),
                "<gold>", "Click to Max Rank").forEach(maxRank::lore);
        gui.set(RANKUP_MAX_SLOT, maxRank.hideAttributes().build(), (clicker, e) -> {
            RankService.RankPreview result = rankService.rankUpMax(clicker);
            if (result.available() <= 0) {
                clicker.sendMessage(Text.parse("<red>You can't afford any more ranks right now.</red>"));
                clicker.playSound(clicker.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            } else {
                announce(clicker, store, result);
            }
            hub.refreshContent(clicker);
        });

        gui.set(RANKUP_CLOSE_SLOT, GuiIcons.closeButton(), (clicker, e) -> clicker.closeInventory());
    }

    private static void announce(Player player, PlayerDataStore<PackPlayerProfile> store, RankService.RankPreview result) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        player.sendMessage(Text.parse("<green>Ranked up <count>x! Now Rank <rank>.</green>",
                Placeholder.unparsed("count", String.valueOf(result.available())),
                Placeholder.unparsed("rank", Formatting.toRoman(profile.getRank()))));
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.2f);
    }
}
