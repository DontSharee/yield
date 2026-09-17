package me.dontshare.yieldpacks.gui;

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
 * Rank is free/automatic - Stars only come from completing the 3 Rank
 * Quests shown in row 0 (fed by yield-quests via {@link RankQuestSource},
 * registered late since yield-packs can't depend on yield-quests). Below
 * that is a paginated grid (36/page, rows 1-4) previewing every rank's
 * diamond multiplier and reward, colored by claim state; the bottom row
 * holds paging plus a claim button for pending rewards. One screen for
 * both "/rankup" and "/rankquests" (same alias-worthy destination now -
 * there's no separate quest GUI anymore).
 */
public final class RankupGui {

    private static final String ACCENT = "<#B15CFF>";
    private static final int TOTAL_ROWS = 6;
    private static final int PAGE_SIZE = 36; // rows 1-4
    private static final int GRID_OFFSET = 9; // row 0 is the quest board
    /** Same reasoning as before - ranks are unbounded, so paging is capped this far past the player's own current-rank page. */
    private static final int MAX_PAGES_AHEAD = 5;
    private static final int QUEST_TITLE_SLOT = 1;
    private static final int[] QUEST_SLOTS = {3, 4, 5};
    private static final int PREV_SLOT = 45;
    private static final int CLAIM_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int HEADER_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final GuiManager guiManager;
    private final RankService rankService;

    /** Registered by yield-quests once it enables - null (and the quest row just shows filler) until then. */
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

        var builder = Gui.builder(TOTAL_ROWS, "Rankup - Page " + (page + 1));
        builder.fill(IntStream.range(0, TOTAL_ROWS * 9)
                .filter(slot -> slot != QUEST_TITLE_SLOT && !isQuestSlot(slot)
                        && (slot < GRID_OFFSET || slot >= GRID_OFFSET + PAGE_SIZE)
                        && slot != PREV_SLOT && slot != CLAIM_SLOT && slot != CLOSE_SLOT
                        && slot != HEADER_SLOT && slot != NEXT_SLOT), GuiIcons.filler());

        builder.item(QUEST_TITLE_SLOT, buildQuestTitleIcon(profile));
        renderQuestSlots(builder, player);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int rank = pageStart + i;
            builder.item(GRID_OFFSET + i, buildRankIcon(profile, rank, currentRank));
        }

        builder.item(HEADER_SLOT, buildHeaderIcon(profile, currentRank));
        builder.item(PREV_SLOT, GuiIcons.pageArrow(false, page > 0), (clicker, event) -> turnPage(clicker, page, -1));
        builder.item(CLAIM_SLOT, buildClaimButton(profile), (clicker, event) -> claim(clicker));
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());
        builder.item(NEXT_SLOT, GuiIcons.pageArrow(true, page < maxPage), (clicker, event) -> turnPage(clicker, page, 1));

        guiManager.open(player, builder.build());
    }

    private boolean isQuestSlot(int slot) {
        for (int questSlot : QUEST_SLOTS) {
            if (questSlot == slot) {
                return true;
            }
        }
        return false;
    }

    private void renderQuestSlots(GuiBuilder builder, Player player) {
        RankQuestSource source = questSource;
        List<RankQuestView> quests = source == null ? List.of() : source.activeQuests(player);
        for (int i = 0; i < QUEST_SLOTS.length; i++) {
            if (i < quests.size()) {
                builder.item(QUEST_SLOTS[i], buildQuestIcon(quests.get(i)));
            } else {
                builder.item(QUEST_SLOTS[i], GuiIcons.filler());
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
        ItemBuilder builder = ItemBuilder.of(Material.KNOWLEDGE_BOOK).name(ACCENT + "&lRank Quests");
        MenuLore.info("rankquests", List.of(
                " &7Finish all 3 quests to the right to",
                " &7roll a fresh set - Stars pay out",
                " &7the instant each one is done."
        ), ACCENT, List.of("Stars: &d" + Formatting.format(profile.getStars()))).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildQuestIcon(RankQuestView quest) {
        String name = Formatting.stripLeadingColorCodes(quest.displayName());
        if (quest.completed()) {
            ItemBuilder builder = ItemBuilder.of(Material.LIME_DYE).name("&a&l" + name + " &a[Done]");
            MenuLore.info("rankquest", List.of(), "<green>", List.of(
                    "Reward: &d" + Formatting.format(quest.rewardStars()) + " Stars",
                    "Waiting on the other 2..."
            )).forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        ItemBuilder builder = ItemBuilder.of(quest.icon()).name("&f&l" + name);
        MenuLore.info("rankquest", List.of(), ACCENT, List.of(
                "Progress: &f" + Math.min(quest.progress(), quest.goal()) + " &7/ &f" + quest.goal(),
                "Reward: &d" + Formatting.format(quest.rewardStars()) + " Stars"
        )).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildHeaderIcon(PackPlayerProfile profile, int currentRank) {
        long into = rankService.starsIntoCurrentRank(profile);
        long needed = rankService.starsForRank(currentRank);
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR)
                .name(ACCENT + "&lRank " + Formatting.toRoman(currentRank));
        MenuLore.info("rank", List.of(), ACCENT, List.of(
                "&7Diamond Multiplier: &b" + Formatting.format(rankService.diamondMultiplier(profile)) + "x",
                "&7Stars: &d" + Formatting.format(into) + " &7/ &d" + Formatting.format(needed)
        )).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildRankIcon(PackPlayerProfile profile, int rank, int currentRank) {
        double multiplier = rankService.diamondMultiplierForRank(rank);
        long rewardCoins = rankService.rewardCoinsForRank(rank);
        long rewardDiamonds = rankService.rewardDiamondsForRank(rank);
        boolean reached = rank <= currentRank;
        boolean claimed = rank <= profile.getClaimedRank();

        if (!reached) {
            ItemBuilder builder = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                    .name("&7&lRank " + Formatting.toRoman(rank) + " &8[Locked]");
            MenuLore.info("rank", List.of(
                    " &7Multiplier: &b" + Formatting.format(multiplier) + "x",
                    " &7Reward: &6" + Formatting.format(rewardCoins) + " coins&7, &b" + Formatting.format(rewardDiamonds) + " diamonds"
            ), "<gray>", List.of("Reach this rank with Stars")).forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        if (claimed) {
            boolean current = rank == currentRank;
            ItemBuilder builder = ItemBuilder.of(current ? Material.LIME_CONCRETE : Material.GREEN_STAINED_GLASS_PANE)
                    .name("&a&lRank " + Formatting.toRoman(rank));
            MenuLore.info("rank", List.of(), "<green>", List.of(
                    "&7Multiplier: &b" + Formatting.format(multiplier) + "x",
                    current ? "&7Your current rank." : "&7Reward claimed."
            )).forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        ItemBuilder builder = ItemBuilder.of(Material.YELLOW_STAINED_GLASS_PANE)
                .name("&e&lRank " + Formatting.toRoman(rank) + " &7[Unclaimed]");
        MenuLore.button(
                "rank",
                List.of(
                        " &7Multiplier: &b" + Formatting.format(multiplier) + "x",
                        " &7Reward: &6" + Formatting.format(rewardCoins) + " coins&7, &b" + Formatting.format(rewardDiamonds) + " diamonds"
                ),
                "<yellow>",
                "Click Claim below to collect"
        ).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildClaimButton(PackPlayerProfile profile) {
        int next = rankService.nextClaimableRank(profile);
        if (next < 0) {
            ItemBuilder builder = ItemBuilder.of(Material.GRAY_DYE).name(MenuLore.buttonName("<gray>", "No Rewards Pending"));
            MenuLore.info("rank", List.of(" &7Earn more Stars from the", " &7quests above to rank up."), "<gray>", List.of()).forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        ItemBuilder builder = ItemBuilder.of(Material.NETHERITE_INGOT).name(MenuLore.buttonName("<gold>", "CLAIM REWARDS"));
        MenuLore.button(
                "rank",
                List.of(" &7Claims every reward you've", " &7reached but not collected yet.",
                        " &7Next: &eRank " + Formatting.toRoman(next)),
                "<gold>",
                "Click to Claim All"
        ).forEach(builder::lore);
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
