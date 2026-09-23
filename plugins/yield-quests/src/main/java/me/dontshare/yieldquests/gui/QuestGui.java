package me.dontshare.yieldquests.gui;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.gui.Gui;
import me.dontshare.yieldcore.gui.GuiBuilder;
import me.dontshare.yieldcore.gui.GuiIcons;
import me.dontshare.yieldcore.gui.GuiManager;
import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.MenuLore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldquests.QuestService;
import me.dontshare.yieldquests.data.QuestCategory;
import me.dontshare.yieldquests.data.QuestContentLoader.QuestContent;
import me.dontshare.yieldquests.data.QuestDefinition;
import me.dontshare.yieldquests.data.QuestDifficulty;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * One row per category. A category not yet committed-to today shows its
 * 3 difficulty tiers (Easy/Medium/Hard) side by side to pick from; clicking
 * one locks it in for the rest of the day (see {@link QuestService#selectDifficulty}) and
 * collapses the row to just that one quest's progress/claim icon.
 */
public final class QuestGui {

    private static final String ACCENT = "<#4BD9FF>";
    private static final int MAX_CATEGORY_ROWS = 4;
    private static final int INFO_SLOT = 4;

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<QuestContent> content;
    private final QuestService questService;
    private final GuiManager guiManager;
    private final Supplier<ItemRegistry> itemRegistry;

    public QuestGui(PlayerDataStore<PackPlayerProfile> store, Supplier<QuestContent> content,
                     QuestService questService, GuiManager guiManager, Supplier<ItemRegistry> itemRegistry) {
        this.store = store;
        this.content = content;
        this.questService = questService;
        this.guiManager = guiManager;
        this.itemRegistry = itemRegistry;
    }

    public void open(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        questService.resetIfNewDay(profile);

        List<QuestCategory> categories = new ArrayList<>(content.get().categories().values());
        int categoryRows = Math.min(MAX_CATEGORY_ROWS, categories.size());
        int totalRows = categoryRows + 2;

        GuiBuilder builder = Gui.builder(totalRows, "Daily Quests");
        builder.fill(IntStream.range(0, 9), GuiIcons.filler());
        builder.item(INFO_SLOT, buildInfoIcon());
        int bottomRowStart = (totalRows - 1) * 9;
        builder.fill(IntStream.range(bottomRowStart, bottomRowStart + 9), GuiIcons.filler());
        builder.item(bottomRowStart + 4, GuiIcons.closeButton(), (clicker, event) -> clicker.closeInventory());

        for (int i = 0; i < categoryRows; i++) {
            buildCategoryRow(builder, (i + 1) * 9, profile, categories.get(i));
        }

        guiManager.open(player, builder.build());
    }

    /**
     * Centered on purpose - 2 filler | category | 1 filler | 3 difficulty
     * slots | 2 filler, so the row reads as one balanced group instead of
     * clustering everything toward the left with a dead gap on the right
     * (the old 1|category|1|3|3 split).
     */
    private void buildCategoryRow(GuiBuilder builder, int base, PackPlayerProfile profile, QuestCategory category) {
        builder.fill(IntStream.of(base, base + 1, base + 3, base + 7, base + 8), GuiIcons.filler());
        builder.item(base + 2, buildCategoryIcon(category));

        QuestDifficulty selected = questService.selectedDifficulty(profile, category.id());
        if (selected == null) {
            for (QuestDifficulty difficulty : QuestDifficulty.values()) {
                QuestDefinition quest = category.tiers().get(difficulty);
                int slot = base + 4 + difficulty.ordinal();
                if (quest == null) {
                    builder.item(slot, GuiIcons.filler());
                    continue;
                }
                builder.item(slot, buildChoiceIcon(category, difficulty, quest),
                        (clicker, event) -> attemptSelect(clicker, category.id(), difficulty));
            }
        } else {
            builder.item(base + 4, GuiIcons.filler());
            builder.item(base + 6, GuiIcons.filler());
            QuestDefinition quest = category.tiers().get(selected);
            builder.item(base + 5, buildActiveIcon(profile, category, selected, quest),
                    (clicker, event) -> attemptClaim(clicker, category.id()));
        }
    }

    private void attemptSelect(Player player, String categoryId, QuestDifficulty difficulty) {
        if (questService.selectDifficulty(player, categoryId, difficulty)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            player.sendMessage(Text.parse("<green>Quest selected!</green>"));
        } else {
            player.sendMessage(Text.parse("<red>You've already picked a quest in this category today.</red>"));
        }
        open(player);
    }

    private void attemptClaim(Player player, String categoryId) {
        QuestDefinition quest = questService.claim(player, categoryId);
        if (quest == null) {
            player.sendMessage(Text.parse("<red>You can't claim this quest yet.</red>"));
        } else {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            StringBuilder extras = new StringBuilder();
            if (quest.rewardDiamonds() > 0) {
                extras.append(", ").append(quest.rewardDiamonds()).append(" diamond(s)");
            }
            if (quest.rewardCredits() > 0) {
                extras.append(", ").append(quest.rewardCredits()).append(" credit(s)");
            }
            if (quest.rewardPetId() != null) {
                itemRegistry.get().find(quest.rewardPetId())
                        .ifPresent(item -> extras.append(", ").append(Formatting.stripLeadingColorCodes(item.displayName())));
            }
            player.sendMessage(Text.parse("<green>Claimed <quest>! +<coins> coins<extras></green>",
                    Placeholder.unparsed("quest", Formatting.stripLeadingColorCodes(quest.displayName())),
                    Placeholder.unparsed("coins", Formatting.format(quest.rewardCoins())),
                    Placeholder.unparsed("extras", extras.toString())));
        }
        open(player);
    }

    private ItemStack buildInfoIcon() {
        ItemBuilder builder = ItemBuilder.of(Material.KNOWLEDGE_BOOK).name(MenuLore.infoName(ACCENT, "HOW THIS WORKS"));
        List<String> description = List.of(
                "&7Pick ONE difficulty per category below.",
                "&7Miss the deadline and you get nothing -",
                "&7finish it before the next reset for the reward.",
                "&7Resets daily at local midnight.");
        MenuLore.info("info", description, ACCENT, List.of()).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildCategoryIcon(QuestCategory category) {
        String name = Formatting.stripLeadingColorCodes(category.displayName());
        ItemBuilder builder = ItemBuilder.of(category.icon()).name(ACCENT + "&l" + name.toUpperCase(java.util.Locale.ROOT));
        List<String> description = List.of("&7Choose Easy, Medium, or Hard", "&7to the right.");
        MenuLore.info("category", description, ACCENT, List.of()).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private ItemStack buildChoiceIcon(QuestCategory category, QuestDifficulty difficulty, QuestDefinition quest) {
        String tierColor = switch (difficulty) {
            case EASY -> "&a";
            case MEDIUM -> "&6";
            case HARD -> "&c";
        };
        ItemBuilder builder = ItemBuilder.of(tierMaterial(difficulty))
                .name(MenuLore.buttonName(tierColor, difficulty.name()));
        List<String> data = List.of(
                "&7Goal: &f" + quest.goal(),
                buildRewardLine(quest)
        );
        MenuLore.button(Formatting.stripLeadingColorCodes(quest.displayName()), data, tierColor,
                "Click to Select").forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    /** "&7Reward: &a$1,500 &7+ &b1 diamond(s) &7+ &d5 credit(s) &7+ &6Diamond Wolf" - each optional clause only appears if that tier actually configures one, so most quests still just show coins (+ diamonds). */
    private String buildRewardLine(QuestDefinition quest) {
        StringBuilder reward = new StringBuilder("&7Reward: &a$" + Formatting.format(quest.rewardCoins()));
        if (quest.rewardDiamonds() > 0) {
            reward.append(" &7+ &b").append(quest.rewardDiamonds()).append(" diamond(s)");
        }
        if (quest.rewardCredits() > 0) {
            reward.append(" &7+ &d").append(quest.rewardCredits()).append(" credit(s)");
        }
        if (quest.rewardPetId() != null) {
            itemRegistry.get().find(quest.rewardPetId())
                    .ifPresent(item -> reward.append(" &7+ ").append(item.displayName()));
        }
        return reward.toString();
    }

    private ItemStack buildActiveIcon(PackPlayerProfile profile, QuestCategory category, QuestDifficulty selected, QuestDefinition quest) {
        int progress = questService.progressOf(profile, category.id(), selected);
        boolean claimed = questService.isClaimed(profile, category.id(), selected);
        boolean ready = progress >= quest.goal();

        List<String> data = new ArrayList<>(List.of(
                "&7Difficulty: &f" + selected.name(),
                "&7Progress: " + MenuLore.progress(Math.min(progress, quest.goal()), quest.goal()),
                buildRewardLine(quest)
        ));

        if (claimed) {
            ItemBuilder builder = ItemBuilder.of(Material.LIME_DYE).name(Formatting.stripLeadingColorCodes(quest.displayName()) + " &a[Claimed]");
            MenuLore.info("quest", data, ACCENT, List.of("Come back tomorrow.")).forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        if (ready) {
            ItemBuilder builder = ItemBuilder.of(Material.EMERALD).name(Formatting.stripLeadingColorCodes(quest.displayName()));
            MenuLore.button("quest", data, ACCENT, "Click to Claim").forEach(builder::lore);
            return builder.hideAttributes().build();
        }
        ItemBuilder builder = ItemBuilder.of(Material.GRAY_DYE).name(Formatting.stripLeadingColorCodes(quest.displayName()));
        data.add("&7Keep going!");
        MenuLore.info("quest", List.of(), ACCENT, data).forEach(builder::lore);
        return builder.hideAttributes().build();
    }

    private Material tierMaterial(QuestDifficulty difficulty) {
        return switch (difficulty) {
            case EASY -> Material.LIME_DYE;
            case MEDIUM -> Material.ORANGE_DYE;
            case HARD -> Material.RED_DYE;
        };
    }
}
