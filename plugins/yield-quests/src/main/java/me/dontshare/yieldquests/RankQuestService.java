package me.dontshare.yieldquests;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldquests.data.GameAction;
import me.dontshare.yieldquests.data.QuestProfile;
import me.dontshare.yieldquests.data.RankQuestDefinition;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * PS99-style rolling quest board - a player always has exactly
 * {@value #ACTIVE_QUEST_COUNT} active quests, picked at random (no
 * replacement) from {@code rank-quests.yml}'s pool. Completing one grants
 * its Stars immediately via {@code RankService#grantStars} - no claim step,
 * unlike the daily {@link QuestService}. Once all 3 active quests are
 * completed, the whole trio instantly rerolls to 3 fresh templates.
 */
public final class RankQuestService {

    private static final int ACTIVE_QUEST_COUNT = 3;

    private final Supplier<List<RankQuestDefinition>> pool;
    private final PlayerDataStore<PackPlayerProfile> store;
    /** This system's own state (active quest ids, progress, completed-this-cycle) - lives on yield-quests' own QuestProfile, same home the daily quest system already uses. */
    private final PlayerDataStore<QuestProfile> questStore;
    private final YieldPacks packs;

    public RankQuestService(Supplier<List<RankQuestDefinition>> pool, PlayerDataStore<PackPlayerProfile> store,
                             PlayerDataStore<QuestProfile> questStore, YieldPacks packs) {
        this.pool = pool;
        this.store = store;
        this.questStore = questStore;
        this.packs = packs;
    }

    /** The player's 3 active quest definitions, rolling a fresh trio first if they have none yet or just finished all 3. */
    public List<RankQuestDefinition> activeQuests(PackPlayerProfile profile) {
        QuestProfile quests = quests(profile);
        if (ensureActive(quests)) {
            questStore.save(profile.getPlayerId());
        }
        return quests.getActiveRankQuestIds().stream()
                .map(this::definitionById)
                .filter(Objects::nonNull)
                .toList();
    }

    public long progressOf(PackPlayerProfile profile, String questId) {
        return quests(profile).getRankQuestProgress().getOrDefault(questId, 0L);
    }

    public boolean isCompleted(PackPlayerProfile profile, String questId) {
        return quests(profile).getCompletedRankQuestIds().contains(questId);
    }

    /** Adds progress toward every active, not-yet-completed quest matching {@code action}; grants Stars immediately on each one reached, and rerolls the trio once all 3 are done. */
    public void incrementProgress(Player player, GameAction action, int amount) {
        if (amount <= 0) {
            return;
        }
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        QuestProfile quests = quests(profile);
        boolean changed = ensureActive(quests);
        for (String id : List.copyOf(quests.getActiveRankQuestIds())) {
            if (quests.getCompletedRankQuestIds().contains(id)) {
                continue;
            }
            RankQuestDefinition definition = definitionById(id);
            if (definition == null || definition.action() != action) {
                continue;
            }
            long progress = quests.getRankQuestProgress().merge(id, (long) amount, Long::sum);
            changed = true;
            if (progress >= definition.goal()) {
                quests.getCompletedRankQuestIds().add(id);
                packs.getRankService().grantStars(player, definition.rewardStars());
            }
        }

        if (allCompleted(quests)) {
            rollNewQuests(quests);
            changed = true;
        }
        if (changed) {
            questStore.save(player.getUniqueId());
        }
    }

    /** Rolls a fresh trio only if there isn't already an in-progress one - first-ever access, or the previous trio was already fully cleared. Returns whether a roll happened. */
    private boolean ensureActive(QuestProfile quests) {
        if (quests.getActiveRankQuestIds().isEmpty() || allCompleted(quests)) {
            rollNewQuests(quests);
            return true;
        }
        return false;
    }

    private boolean allCompleted(QuestProfile quests) {
        return !quests.getActiveRankQuestIds().isEmpty()
                && quests.getCompletedRankQuestIds().containsAll(quests.getActiveRankQuestIds());
    }

    private void rollNewQuests(QuestProfile quests) {
        List<RankQuestDefinition> options = new ArrayList<>(pool.get());
        Collections.shuffle(options);
        List<RankQuestDefinition> picked = options.subList(0, Math.min(ACTIVE_QUEST_COUNT, options.size()));

        quests.getActiveRankQuestIds().clear();
        quests.getCompletedRankQuestIds().clear();
        for (RankQuestDefinition definition : picked) {
            quests.getActiveRankQuestIds().add(definition.id());
            quests.getRankQuestProgress().remove(definition.id());
        }
    }

    private RankQuestDefinition definitionById(String id) {
        return pool.get().stream().filter(definition -> definition.id().equals(id)).findFirst().orElse(null);
    }

    /** This plugin's own record for whoever the given pack profile belongs to. */
    private QuestProfile quests(PackPlayerProfile profile) {
        return questStore.getOrCreate(profile.getPlayerId());
    }
}
