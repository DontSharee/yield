package me.dontshare.yieldpacks.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiLayout;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.rank.RankQuestSource;
import me.dontshare.yieldpacks.rank.RankQuestSource.RankQuestView;
import me.dontshare.yieldpacks.rank.RankService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * /rankup: the rank ladder as a grid, in the house frame.
 * <p>
 * The top row holds the three active Rank Quests (fed by yield-quests via
 * {@link RankQuestSource}, registered late since yield-packs can't depend
 * on yield-quests). Inside the frame, 28 ranks a page as panes whose stack
 * size is the rank number: yellow and glowing for the rank you're on,
 * lime for claimed, orange for reached-but-unclaimed (click to claim),
 * red for not reached yet. The bottom row is Claim All, the page arrows
 * either side of your rank, and the quest board. One screen for both
 * "/rankup" and "/rankquests".
 */
public final class RankupGui {

    private static final String ACCENT = "<#B15CFF>";
    private static final int TOTAL_ROWS = 6;
    /** Four centred rows of seven inside the frame. */
    private static final int PAGE_SIZE = GuiLayout.capacity(4);
    /** Same reasoning as before - ranks are unbounded, so paging is capped this far past the player's own current-rank page. */
    private static final int MAX_PAGES_AHEAD = 5;
    private static final int[] QUEST_SLOTS = {3, 4, 5};
    private static final int CLAIM_SLOT = 45;
    private static final int PREV_SLOT = 47;
    private static final int HEADER_SLOT = 49;
    private static final int NEXT_SLOT = 51;
    private static final int QUEST_INFO_SLOT = 53;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final GuiManager guiManager;
    private final RankService rankService;

    /** Registered by yield-quests once it enables - null (and the quest row just shows frame) until then. */
    private volatile RankQuestSource questSource;

    /** Which page each player is currently viewing - reset to their current rank's own page every time {@link #open} is called fresh, but preserved across a page turn or a claim so those don't yank the view somewhere else. */
    private final Map<UUID, Integer> pageIndex = new ConcurrentHashMap<>();

    public RankupGui(PlayerDataStore<PackPlayerProfile> store, GuiManager guiManager, RankService rankService) {
        this.store = store;
        this.guiManager = guiManager;
        this.rankService = rankService;
    }

    /** Called by yield-quests' onEnable - lets this GUI show the player's 3 active Rank Quests inline instead of needing its own separate screen/command. */
    public void setQuestSource(RankQuestSource questSource) {
        this.questSource = questSource;
    }

    /** Fresh entry point - always jumps to the page that contains the player's CURRENT rank, so a high-rank player never has to page-hunt for where they actually are. */
    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        pageIndex.put(player.getUniqueId(), rankService.rankOf(profile) / PAGE_SIZE);
        render(player);
    }

    /** Redraws at whatever page is already stored - used by every internal refresh (page turn, a claim) so those never reset the player's own place in the list. */
    private void render(Player player) {
        UUID uuid = player.getUniqueId();
        PackPlayerProfile profile = store.getOrCreate(uuid);
        int currentRank = rankService.rankOf(profile);
        int maxPage = currentRank / PAGE_SIZE + MAX_PAGES_AHEAD;
        int page = Math.min(maxPage, Math.max(0, pageIndex.getOrDefault(uuid, 0)));
        pageIndex.put(uuid, page);
        int pageStart = page * PAGE_SIZE;

        var builder = Gui.builder(TOTAL_ROWS, "Rankup (" + (page + 1) + ")");
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9), GuiIcons.filler());
        renderQuestSlots(builder, player);

        int[] slots = GuiLayout.centered(1, PAGE_SIZE);
        for (int i = 0; i < PAGE_SIZE; i++) {
            int rank = pageStart + i;
            boolean claimable = rank <= currentRank && rank > profile.getClaimedRank();
            if (claimable) {
                builder.item(slots[i], buildRankIcon(profile, rank, currentRank), (clicker, event) -> claim(clicker));
            } else {
                builder.item(slots[i], buildRankIcon(profile, rank, currentRank));
            }
        }

        builder.item(CLAIM_SLOT, buildClaimButton(profile), (clicker, event) -> claim(clicker));
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page > 0), (clicker, event) -> turnPage(clicker, page, -1));
        builder.item(HEADER_SLOT, buildHeaderIcon(profile, currentRank));
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page < maxPage), (clicker, event) -> turnPage(clicker, page, 1));
        builder.item(QUEST_INFO_SLOT, buildQuestTitleIcon(profile));

        guiManager.open(player, builder.build());
    }

    private void renderQuestSlots(GuiBuilder builder, Player player) {
        RankQuestSource source = questSource;
        List<RankQuestView> quests = source == null ? List.of() : source.activeQuests(player);
        for (int i = 0; i < QUEST_SLOTS.length; i++) {
            if (i < quests.size()) {
                builder.item(QUEST_SLOTS[i], buildQuestIcon(quests.get(i)));
            }
        }
    }

    /** Ranks are infinite, but viewing is capped at {@link #MAX_PAGES_AHEAD} past the player's own current-rank page. */
    private void turnPage(Player player, int currentPage, int delta) {
        int next = Math.max(0, currentPage + delta);
        pageIndex.put(player.getUniqueId(), next);
        render(player);
    }

    private ItemStack buildQuestTitleIcon(PackPlayerProfile profile) {
        ItemBuilder builder = ItemBuilder.of(Material.KNOWLEDGE_BOOK).name(MenuLore.infoName(ACCENT, "Rank Quests"));
        MenuLore.info("rank quests", List.of(
                "Finish all 3 quests above to roll",
                "a fresh set. Stars pay out the",
                "moment each one is done."
        ), ACCENT, List.of("Stars: &d" + Formatting.format(profile.getStars()))).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildQuestIcon(RankQuestView quest) {
        String name = Formatting.stripLeadingColorCodes(quest.displayName());
        if (quest.completed()) {
            ItemBuilder builder = ItemBuilder.of(Material.LIME_DYE).name(MenuLore.name("&a", name) + " &7[Done]");
            MenuLore.info("rank quest", List.of("Waiting on the other two."), "&a", List.of(
                    "Reward: &d" + Formatting.format(quest.rewardStars()) + " Stars",
                    "Status: &aDone"
            )).forEach(builder::lore);
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
            return builder.hideAttributes().build();
        }
        ItemBuilder builder = ItemBuilder.of(quest.icon()).name(MenuLore.name(ACCENT, name));
        MenuLore.info("rank quest", List.of(), ACCENT, List.of(
                "Progress: " + MenuLore.progress(Math.min(quest.progress(), quest.goal()), quest.goal()),
                "Reward: &d" + Formatting.format(quest.rewardStars()) + " Stars"
        )).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildHeaderIcon(PackPlayerProfile profile, int currentRank) {
        long into = rankService.starsIntoCurrentRank(profile);
        long needed = rankService.starsForRank(currentRank);
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR)
                .name(MenuLore.infoName(ACCENT, "Your Rank") + " &7[" + Formatting.toRoman(currentRank) + "]");
        MenuLore.info("rank", List.of("Earn Stars from Rank Quests", "to climb the ladder."), ACCENT, List.of(
                "Rank: &f" + currentRank,
                "Diamond Multi: &b" + Formatting.format(rankService.diamondMultiplier(profile)) + "x",
                "Next Rank: " + MenuLore.progress(into, needed) + " &7Stars"
        )).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildRankIcon(PackPlayerProfile profile, int rank, int currentRank) {
        double multiplier = rankService.diamondMultiplierForRank(rank);
        long rewardCoins = rankService.rewardCoinsForRank(rank);
        long rewardDiamonds = rankService.rewardDiamondsForRank(rank);
        boolean reached = rank <= currentRank;
        boolean claimed = rank <= profile.getClaimedRank();
        boolean current = rank == currentRank;

        Material pane;
        String color;
        String status;
        if (!reached) {
            pane = Material.RED_STAINED_GLASS_PANE;
            color = "&c";
            status = "&cLocked";
        } else if (!claimed) {
            pane = Material.ORANGE_STAINED_GLASS_PANE;
            color = "&6";
            status = "&6Reward ready";
        } else if (current) {
            pane = Material.YELLOW_STAINED_GLASS_PANE;
            color = "&e";
            status = "&eYour rank";
        } else {
            pane = Material.LIME_STAINED_GLASS_PANE;
            color = "&a";
            status = "&aClaimed";
        }
        ItemBuilder builder = ItemBuilder.of(pane)
                .amount(Math.max(1, Math.min(64, rank)))
                .name(MenuLore.name(color, "Rank") + " &7[" + Formatting.toRoman(rank) + "]");
        List<String> data = List.of(
                "Diamond Multi: &b" + Formatting.format(multiplier) + "x",
                "Reward: &6" + Formatting.format(rewardCoins) + " &7coins, &b" + Formatting.format(rewardDiamonds) + " &7diamonds",
                "Status: " + status);
        if (reached && !claimed) {
            MenuLore.button("rank", List.of(), "&6", data, "Click to claim").forEach(builder::lore);
        } else {
            MenuLore.info("rank", List.of(), color, data).forEach(builder::lore);
        }
        if (current || (reached && !claimed)) {
            builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        }
        return builder.hideAttributes().build();
    }

    private ItemStack buildClaimButton(PackPlayerProfile profile) {
        int next = rankService.nextClaimableRank(profile);
        if (next < 0) {
            ItemBuilder builder = ItemBuilder.of(Material.GRAY_DYE).name(MenuLore.infoName("&7", "No Rewards Pending"));
            MenuLore.info("rank", List.of("Earn more Stars from the", "quests above to rank up."), "&7", List.of()).forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        ItemBuilder builder = ItemBuilder.of(Material.NETHERITE_INGOT).name(MenuLore.buttonName("&6", "Claim Rewards"));
        MenuLore.button("rank", List.of("Claims every rank reward you've", "reached but not collected."),
                "&6", List.of("Next: &eRank " + Formatting.toRoman(next)), "Click to claim all").forEach(builder::lore);
        builder.enchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
        return builder.hideAttributes().build();
    }

    private void claim(Player player) {
        int count = rankService.claimAllRewards(player);
        if (count <= 0) {
            player.sendMessage(Text.parse("<red>Nothing to claim yet.</red>"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        } else {
            PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
            player.sendMessage(Text.parse("<green>Claimed <count> rank reward(s)! Now Rank <rank>.</green>",
                    Placeholder.unparsed("count", String.valueOf(count)),
                    Placeholder.unparsed("rank", Formatting.toRoman(profile.getClaimedRank()))));
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.2f);
        }
        render(player);
    }
}
