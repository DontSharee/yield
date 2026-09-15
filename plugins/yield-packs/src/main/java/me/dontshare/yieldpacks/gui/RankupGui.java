package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.rank.RankService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * Spend diamonds on permanent Rank - a full paginated grid of every rank (36 per
 * page, rows 0-3), each slot showing that rank's own Roman numeral and diamond
 * multiplier, red stained glass while still locked. Clicking any locked
 * slot buys straight up through it (see {@code RankService#rankUpTo}); the
 * bottom row holds paging plus a Max Rank bulk-buy.
 */
public final class RankupGui {

    private static final String ACCENT = "<#B15CFF>";
    private static final int TOTAL_ROWS = 5;
    private static final int PAGE_SIZE = 36; // rows 0-3
    /**
     * How far past the player's OWN current-rank page Next can go. Ranks
     * are unbounded, so without a cap here, spam-clicking Next could reach
     * an astronomically distant page - and every locked slot's cost
     * ({@link #costUpTo}) sums one {@link RankService#costForRank} call per
     * rank between the player's real current rank and that slot, so a page
     * thousands of ranks away was genuinely expensive to render, not just
     * pointless to look at.
     */
    private static final int MAX_PAGES_AHEAD = 5;
    private static final int PREV_SLOT = 36;
    private static final int MAX_RANK_SLOT = 38;
    private static final int CLOSE_SLOT = 40;
    private static final int HEADER_SLOT = 42;
    private static final int NEXT_SLOT = 44;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final GuiManager guiManager;
    private final RankService rankService;

    /** Which page each player is currently viewing - reset to their current rank's own page every time {@link #open} is called fresh (walking into the ring again, or re-running the command), but preserved across a page turn or a purchase so those don't yank the view somewhere else. */
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public RankupGui(PlayerDataStore<PackPlayerProfile> store, GuiManager guiManager, RankService rankService) {
        this.store = store;
        this.guiManager = guiManager;
        this.rankService = rankService;
    }

    /** Fresh entry point - always jumps to the page that contains the player's CURRENT rank, so a high-rank player never has to page-hunt for where they actually are. */
    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        pageIndex.put(player.getUniqueId(), profile.getRank() / PAGE_SIZE);
        render(player);
    }

    /** Redraws at whatever page is already stored - used by every internal refresh (page turn, a purchase) so those never reset the player's own place in the list. */
    private void render(Player player) {
        UUID uuid = player.getUniqueId();
        PackPlayerProfile profile = store.getOrCreate(uuid);
        int maxPage = profile.getRank() / PAGE_SIZE + MAX_PAGES_AHEAD;
        int page = Math.min(maxPage, Math.max(0, pageIndex.getOrDefault(uuid, 0)));
        pageIndex.put(uuid, page);
        int pageStart = page * PAGE_SIZE;

        var builder = Gui.builder(TOTAL_ROWS, "Rankup - Page " + (page + 1));
        builder.fill(IntStream.range(PAGE_SIZE, TOTAL_ROWS * 9)
                .filter(slot -> slot != PREV_SLOT && slot != MAX_RANK_SLOT && slot != CLOSE_SLOT
                        && slot != HEADER_SLOT && slot != NEXT_SLOT), GuiIcons.filler());

        for (int i = 0; i < PAGE_SIZE; i++) {
            int rank = pageStart + i;
            ItemStack icon = buildRankIcon(profile, rank);
            if (rank > profile.getRank()) {
                builder.item(i, icon, (clicker, event) -> rankUpTo(clicker, rank));
            } else {
                builder.item(i, icon);
            }
        }

        builder.item(HEADER_SLOT, buildHeaderIcon(profile));
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page > 0), (clicker, event) -> turnPage(clicker, page, -1));
        builder.item(MAX_RANK_SLOT, buildMaxRankButton(profile), (clicker, event) -> rankUpMax(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page < maxPage), (clicker, event) -> turnPage(clicker, page, 1));

        guiManager.open(player, builder.build());
    }

    /** Ranks are infinite, but viewing is capped at {@link #MAX_PAGES_AHEAD} past the player's own current-rank page - {@link #render} itself re-clamps against that (it needs the player's live rank to compute the bound anyway), so this only needs to floor at 0. */
    private void turnPage(Player player, int currentPage, int delta) {
        int next = Math.max(0, currentPage + delta);
        pageIndex.put(player.getUniqueId(), next);
        render(player);
    }

    private ItemStack buildHeaderIcon(PackPlayerProfile profile) {
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR)
                .name(ACCENT + "&lRank " + Formatting.toRoman(profile.getRank()));
        MenuLore.info("rank", List.of(), ACCENT, List.of(
                "&7Diamond Multiplier: &b" + Formatting.format(rankService.diamondMultiplier(profile)) + "x",
                "&7Your Diamonds: &b" + Formatting.format(profile.getDiamonds())
        )).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildRankIcon(PackPlayerProfile profile, int rank) {
        double multiplier = rankService.diamondMultiplierForRank(rank);
        boolean owned = rank <= profile.getRank();
        if (owned) {
            boolean current = rank == profile.getRank();
            ItemBuilder builder = ItemBuilder.of(current ? Material.LIME_CONCRETE : Material.GREEN_STAINED_GLASS_PANE)
                    .name("&a&lRank " + Formatting.toRoman(rank));
            MenuLore.info("rank", List.of(), "<green>", List.of(
                    "&7Multiplier: &b" + Formatting.format(multiplier) + "x",
                    current ? "&7Your current rank." : "&7Already reached."
            )).forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        BigInteger cost = costUpTo(profile, rank);
        boolean affordable = profile.getDiamonds().compareTo(cost) >= 0;
        ItemBuilder builder = ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                .name((affordable ? "&e&l" : "&c&l") + "Rank " + Formatting.toRoman(rank) + " &7[Locked]");
        MenuLore.button(
                "rank",
                List.of(
                        " &7Multiplier: &b" + Formatting.format(multiplier) + "x",
                        " &7Cost from here: &b" + Formatting.format(cost) + " diamonds"
                ),
                affordable ? "<yellow>" : "<red>",
                affordable ? "Click to Unlock" : "Not Enough Diamonds"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /** Combined diamond cost to go from the player's CURRENT rank up through {@code targetRank} - what clicking that slot actually pays, not just that one rank's marginal cost. */
    private BigInteger costUpTo(PackPlayerProfile profile, int targetRank) {
        BigInteger total = BigInteger.ZERO;
        for (int rank = profile.getRank(); rank < targetRank; rank++) {
            total = total.add(rankService.costForRank(rank));
        }
        return total;
    }

    private ItemStack buildMaxRankButton(PackPlayerProfile profile) {
        RankService.RankPreview preview = rankService.preview(profile);
        ItemBuilder builder = ItemBuilder.of(Material.NETHERITE_INGOT).name(MenuLore.buttonName("<gold>", "MAX RANK"));
        MenuLore.button(
                "rank",
                List.of(" &7Buys every rank you can", " &7currently afford at once.",
                        " &7Available now: &e" + preview.available()),
                "<gold>",
                "Click to Max Rank"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private void rankUpTo(Player player, int targetRank) {
        RankService.RankPreview result = rankService.rankUpTo(player, targetRank);
        if (result.available() <= 0) {
            player.sendMessage(Text.parse("<red>You can't afford that yet.</red>"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        } else {
            announce(player, result);
        }
        render(player);
    }

    private void rankUpMax(Player player) {
        RankService.RankPreview result = rankService.rankUpMax(player);
        if (result.available() <= 0) {
            player.sendMessage(Text.parse("<red>You can't afford any more ranks right now.</red>"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        } else {
            announce(player, result);
        }
        render(player);
    }

    private void announce(Player player, RankService.RankPreview result) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        player.sendMessage(Text.parse("<green>Ranked up <count>x! Now Rank <rank>.</green>",
                Placeholder.unparsed("count", String.valueOf(result.available())),
                Placeholder.unparsed("rank", Formatting.toRoman(profile.getRank()))));
        // "A new power tier selected" - the exact same sound a beacon
        // makes confirming a chosen effect, distinct from the generic
        // level-up jingle used for the player's own separate XP/level stat.
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.2f);
    }
}
