package me.dontshare.yieldquests.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldquests.RankQuestService;
import me.dontshare.yieldquests.data.RankQuestDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Read-only board - quests complete themselves from real gameplay events
 * (see {@code QuestEventListener}), so there's nothing to click here except
 * Close. Header shows total Stars and derived rank; 3 fixed slots show each
 * active quest's own progress, gray while in progress and lime once
 * completed (about to reroll the whole trio together).
 */
public final class RankQuestGui {

    private static final String ACCENT = "<#B15CFF>";
    private static final int HEADER_SLOT = 4;
    private static final int[] QUEST_SLOTS = {11, 13, 15};
    private static final int CLOSE_SLOT = 22;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final RankQuestService rankQuestService;
    private final YieldPacks packs;
    private final GuiManager guiManager;

    public RankQuestGui(PlayerDataStore<PackPlayerProfile> store, RankQuestService rankQuestService,
                         YieldPacks packs, GuiManager guiManager) {
        this.store = store;
        this.rankQuestService = rankQuestService;
        this.packs = packs;
        this.guiManager = guiManager;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        List<RankQuestDefinition> active = rankQuestService.activeQuests(profile);

        var builder = Gui.builder(3, "Rank Quests");
        builder.fill(IntStream.range(0, 27), GuiIcons.filler());
        builder.item(HEADER_SLOT, buildHeaderIcon(profile));
        for (int i = 0; i < QUEST_SLOTS.length; i++) {
            if (i < active.size()) {
                builder.item(QUEST_SLOTS[i], buildQuestIcon(profile, active.get(i)));
            }
        }
        builder.item(CLOSE_SLOT, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        guiManager.open(player, builder.build());
    }

    private ItemStack buildHeaderIcon(PackPlayerProfile profile) {
        int rank = packs.getRankService().rankOf(profile);
        ItemBuilder builder = ItemBuilder.of(Material.NETHER_STAR)
                .name(ACCENT + "&lRank Quests");
        MenuLore.info("rankquests", List.of(
                " &7Finish all 3 quests below to",
                " &7roll a fresh set - Stars pay",
                " &7out the instant each one is done."
        ), ACCENT, List.of(
                "Stars: &d" + Formatting.format(profile.getStars()),
                "Current Rank: &d" + Formatting.toRoman(rank)
        )).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildQuestIcon(PackPlayerProfile profile, RankQuestDefinition quest) {
        long progress = rankQuestService.progressOf(profile, quest.id());
        boolean completed = rankQuestService.isCompleted(profile, quest.id());
        String name = Formatting.stripLeadingColorCodes(quest.displayName());

        if (completed) {
            ItemBuilder builder = ItemBuilder.of(Material.LIME_DYE).name("&a&l" + name + " &a[Done]");
            MenuLore.info("rankquest", List.of(), "<green>", List.of(
                    "Reward: &d" + Formatting.format(quest.rewardStars()) + " Stars",
                    "Waiting on the other 2..."
            )).forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        ItemBuilder builder = ItemBuilder.of(quest.icon()).name("&f&l" + name);
        MenuLore.info("rankquest", List.of(), ACCENT, List.of(
                "Progress: &f" + Math.min(progress, quest.goal()) + " &7/ &f" + quest.goal(),
                "Reward: &d" + Formatting.format(quest.rewardStars()) + " Stars"
        )).forEach(builder::lore);
        return builder.hideAttributes().build();
    }
}
