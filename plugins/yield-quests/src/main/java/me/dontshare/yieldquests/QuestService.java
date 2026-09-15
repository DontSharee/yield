package me.dontshare.yieldquests;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldquests.data.QuestProfile;
import me.dontshare.yieldquests.data.GameAction;
import me.dontshare.yieldquests.data.QuestCategory;
import me.dontshare.yieldquests.data.QuestContentLoader.QuestContent;
import me.dontshare.yieldquests.data.QuestDefinition;
import me.dontshare.yieldquests.data.QuestDifficulty;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Categories (combat, economy, ...), each offering an EASY/MEDIUM/HARD
 * tier - a player picks exactly ONE tier per category per day
 * ({@link #selectDifficulty}), locked in until the next reset. Progress
 * only ever accrues toward a category the player has actually committed
 * to; missing the goal by the next reset means no reward at all for that
 * category that day.
 */
public final class QuestService {

    private final Supplier<QuestContent> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    /** This plugin's own quest state. The pack store above stays for the rewards a claim pays out. */
    private final PlayerDataStore<QuestProfile> questStore;
    private final YieldPacks packs;

    public QuestService(Supplier<QuestContent> content, PlayerDataStore<PackPlayerProfile> store,
                         PlayerDataStore<QuestProfile> questStore, YieldPacks packs) {
        this.content = content;
        this.store = store;
        this.questStore = questStore;
        this.packs = packs;
    }

    /** Clears today's progress/claims/selections if the last reset was on an earlier day. */
    public void resetIfNewDay(PackPlayerProfile profile) {
        long today = LocalDate.now().toEpochDay();
        if (quests(profile).getLastResetEpochDay() != today) {
            quests(profile).getProgress().clear();
            quests(profile).getClaimedIds().clear();
            quests(profile).getSelectedDifficultyByCategory().clear();
            quests(profile).setLastResetEpochDay(today);
        }
    }

    public QuestDifficulty selectedDifficulty(PackPlayerProfile profile, String categoryId) {
        String raw = quests(profile).getSelectedDifficultyByCategory().get(categoryId);
        if (raw == null) {
            return null;
        }
        try {
            return QuestDifficulty.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Commits to a difficulty for this category for the rest of the day - a no-op (returns false) if one's already been picked today. */
    public boolean selectDifficulty(Player player, String categoryId, QuestDifficulty difficulty) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        resetIfNewDay(profile);
        if (selectedDifficulty(profile, categoryId) != null) {
            return false;
        }
        quests(profile).getSelectedDifficultyByCategory().put(categoryId, difficulty.name());
        store.save(player.getUniqueId());
        questStore.save(player.getUniqueId());
        return true;
    }

    /** Adds progress toward whichever tier the player has committed to in each category matching {@code action} - a no-op for a category they haven't picked a quest in yet today. */
    public void incrementProgress(Player player, GameAction action, int amount) {
        if (amount <= 0) {
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        resetIfNewDay(profile);
        boolean changed = false;
        for (QuestCategory category : content.get().categories().values()) {
            QuestDifficulty selected = selectedDifficulty(profile, category.id());
            if (selected == null) {
                continue;
            }
            QuestDefinition quest = category.tiers().get(selected);
            if (quest == null || quest.action() != action) {
                continue;
            }
            quests(profile).getProgress().merge(questId(category.id(), selected), amount, Integer::sum);
            changed = true;
        }
        if (changed) {
            store.save(player.getUniqueId());
        questStore.save(player.getUniqueId());
        }
    }

    public boolean canClaim(PackPlayerProfile profile, String categoryId) {
        QuestDifficulty selected = selectedDifficulty(profile, categoryId);
        if (selected == null) {
            return false;
        }
        String questId = questId(categoryId, selected);
        if (quests(profile).getClaimedIds().contains(questId)) {
            return false;
        }
        QuestCategory category = content.get().categories().get(categoryId);
        QuestDefinition quest = category != null ? category.tiers().get(selected) : null;
        if (quest == null) {
            return false;
        }
        return quests(profile).getProgress().getOrDefault(questId, 0) >= quest.goal();
    }

    /**
     * Grants the committed tier's reward (coin-multiplier-adjusted, same as
     * every other coin source) and marks it claimed. A configured
     * {@code rewardPetId} is granted straight into the Bag as a fresh
     * level-1 instance - deliberately NOT run through
     * {@link PackPlayerProfile#addOwnedItem}, since that also stamps pack
     * collection progress meant for pets actually pulled from a roll; a
     * curated quest reward is a different acquisition path and shouldn't be
     * conflated with "got lucky in a pack." Returns the quest, or null if it
     * couldn't be claimed.
     */
    public QuestDefinition claim(Player player, String categoryId) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        resetIfNewDay(profile);
        if (!canClaim(profile, categoryId)) {
            return null;
        }
        QuestDifficulty selected = selectedDifficulty(profile, categoryId);
        QuestDefinition quest = content.get().categories().get(categoryId).tiers().get(selected);
        long coins = Math.round(quest.rewardCoins() * packs.coinMultiplier(profile));
        profile.setCoins(profile.getCoins().add(BigInteger.valueOf(coins)));
        profile.setDiamonds(profile.getDiamonds().add(BigInteger.valueOf(quest.rewardDiamonds())));
        if (quest.rewardCredits() > 0) {
            profile.setCredits(profile.getCredits().add(BigInteger.valueOf(quest.rewardCredits())));
        }
        if (quest.rewardPetId() != null && packs.getItemRegistry().find(quest.rewardPetId()).isPresent()) {
            profile.addPet(new PetInstance(UUID.randomUUID(), quest.rewardPetId()));
        }
        quests(profile).getClaimedIds().add(questId(categoryId, selected));
        store.save(player.getUniqueId());
        questStore.save(player.getUniqueId());
        return quest;
    }

    public int progressOf(PackPlayerProfile profile, String categoryId, QuestDifficulty difficulty) {
        return quests(profile).getProgress().getOrDefault(questId(categoryId, difficulty), 0);
    }

    public boolean isClaimed(PackPlayerProfile profile, String categoryId, QuestDifficulty difficulty) {
        return quests(profile).getClaimedIds().contains(questId(categoryId, difficulty));
    }

    private static String questId(String categoryId, QuestDifficulty difficulty) {
        return categoryId + ":" + difficulty.name();
    }

    /**
     * Admin/support override - commits {@code player} to {@code difficulty}
     * in {@code categoryId} (if they hadn't already picked one today),
     * maxes its progress, and claims it immediately. Returns the quest, or
     * null if that category/difficulty doesn't exist or it's already been
     * claimed today.
     */
    public QuestDefinition forceComplete(Player player, String categoryId, QuestDifficulty difficulty) {
        QuestCategory category = content.get().categories().get(categoryId);
        QuestDefinition quest = category != null ? category.tiers().get(difficulty) : null;
        if (quest == null) {
            return null;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        resetIfNewDay(profile);
        if (isClaimed(profile, categoryId, difficulty)) {
            return null;
        }
        quests(profile).getSelectedDifficultyByCategory().put(categoryId, difficulty.name());
        quests(profile).getProgress().put(questId(categoryId, difficulty), quest.goal());
        store.save(player.getUniqueId());
        questStore.save(player.getUniqueId());
        return claim(player, categoryId);
    }

    /** This plugin's own record for whoever the given pack profile belongs to. */
    private QuestProfile quests(PackPlayerProfile profile) {
        return questStore.getOrCreate(profile.getPlayerId());
    }
}
