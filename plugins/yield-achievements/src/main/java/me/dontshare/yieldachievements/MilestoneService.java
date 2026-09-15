package me.dontshare.yieldachievements;

import me.dontshare.yieldachievements.data.GameAction;
import me.dontshare.yieldachievements.data.MilestoneCategory;
import me.dontshare.yieldachievements.data.MilestoneTier;
import me.dontshare.yieldachievements.potion.PotionDefinition;
import me.dontshare.yieldachievements.potion.PotionItem;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldachievements.data.AchievementProfile;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Category-based progress ladders - every tier in a category tracks the
 * SAME shared counter (see {@code MilestoneCategory}), so several tiers can
 * become complete-but-unclaimed at once if progress jumps past more than
 * one threshold in a single trigger (e.g. a huge coin payout). Unlike
 * {@link AchievementService}, claiming is an explicit player action - see
 * {@link #claim}.
 */
public final class MilestoneService {

    public enum TierState {
        /** No progress at all yet toward this tier - OR the previous tier in this category hasn't been claimed yet (see {@link #stateOf}) - a ladder claims one rung at a time, even if raw progress already clears several at once. */
        INCOMPLETE,
        /** The previous tier is claimed, and there's some progress, but short of this tier's goal. */
        IN_PROGRESS,
        /** The previous tier is claimed and this one's goal is reached - ready to claim. */
        COMPLETE_UNCLAIMED,
        /** Goal reached and already claimed. */
        CLAIMED
    }

    public enum ClaimResult {
        SUCCESS,
        NOT_COMPLETE,
        ALREADY_CLAIMED,
        LOCKED,
        UNKNOWN
    }

    private final Supplier<Map<String, MilestoneCategory>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    /** This plugin's own progress and effects. The pack store above stays for the rewards a claim pays out. */
    private final PlayerDataStore<AchievementProfile> achievementStore;
    private final PotionItem potionItem;

    public MilestoneService(Supplier<Map<String, MilestoneCategory>> content, PlayerDataStore<PackPlayerProfile> store,
                             PlayerDataStore<AchievementProfile> achievementStore, PotionItem potionItem) {
        this.content = content;
        this.store = store;
        this.achievementStore = achievementStore;
        this.potionItem = potionItem;
    }

    public void incrementProgress(Player player, GameAction action, long amount) {
        if (amount <= 0) {
            return;
        }
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        boolean changed = false;
        for (MilestoneCategory category : content.get().values()) {
            if (category.trigger() != action) {
                continue;
            }
            achievementStore.getOrCreate(profile.getPlayerId()).getMilestoneProgress().merge(category.id(), amount, Long::sum);
            changed = true;
        }
        if (changed) {
            store.save(player.getUniqueId());
        achievementStore.save(player.getUniqueId());
        }
    }

    public long progressOf(PackPlayerProfile profile, String categoryId) {
        return achievementStore.getOrCreate(profile.getPlayerId()).getMilestoneProgress().getOrDefault(categoryId, 0L);
    }

    public TierState stateOf(PackPlayerProfile profile, String categoryId, int tierIndex, MilestoneTier tier) {
        if (achievementStore.getOrCreate(profile.getPlayerId()).getClaimedMilestoneKeys().contains(key(categoryId, tierIndex))) {
            return TierState.CLAIMED;
        }
        if (!previousClaimed(profile, categoryId, tierIndex)) {
            // A ladder is claimed one rung at a time - even if raw progress
            // already clears this tier's goal (and the one after it), it
            // stays locked/red until whatever comes before it is claimed.
            return TierState.INCOMPLETE;
        }
        long progress = progressOf(profile, categoryId);
        if (progress >= tier.goal()) {
            return TierState.COMPLETE_UNCLAIMED;
        }
        return progress > 0 ? TierState.IN_PROGRESS : TierState.INCOMPLETE;
    }

    private boolean previousClaimed(PackPlayerProfile profile, String categoryId, int tierIndex) {
        return tierIndex == 0 || achievementStore.getOrCreate(profile.getPlayerId()).getClaimedMilestoneKeys().contains(key(categoryId, tierIndex - 1));
    }

    public ClaimResult claim(Player player, String categoryId, int tierIndex) {
        MilestoneCategory category = content.get().get(categoryId);
        if (category == null || tierIndex < 0 || tierIndex >= category.tiers().size()) {
            return ClaimResult.UNKNOWN;
        }
        MilestoneTier tier = category.tiers().get(tierIndex);
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        String key = key(categoryId, tierIndex);
        if (achievementStore.getOrCreate(profile.getPlayerId()).getClaimedMilestoneKeys().contains(key)) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        if (!previousClaimed(profile, categoryId, tierIndex)) {
            return ClaimResult.LOCKED;
        }
        if (progressOf(profile, categoryId) < tier.goal()) {
            return ClaimResult.NOT_COMPLETE;
        }
        achievementStore.getOrCreate(profile.getPlayerId()).getClaimedMilestoneKeys().add(key);
        profile.setCoins(profile.getCoins().add(tier.rewardCoins()));
        profile.setDiamonds(profile.getDiamonds().add(tier.rewardDiamonds()));
        profile.setCredits(profile.getCredits().add(tier.rewardCredits()));
        store.save(player.getUniqueId());
        achievementStore.save(player.getUniqueId());

        if (tier.rewardPotionId() != null) {
            PotionDefinition def = PotionDefinition.parse(tier.rewardPotionId());
            if (def != null) {
                player.getInventory().addItem(potionItem.create(def));
            }
        }
        return ClaimResult.SUCCESS;
    }

    private String key(String categoryId, int tierIndex) {
        return categoryId + ":" + tierIndex;
    }

    /**
     * Admin/support override - claims tier {@code tierIndex} of {@code
     * categoryId} for {@code player} immediately, regardless of their
     * actual progress. Any EARLIER tier that isn't already claimed gets
     * marked claimed too (without its own reward) purely so the sequential-
     * lock invariant ({@link #previousClaimed}) stays consistent - the
     * checklist would otherwise show tier 2 claimed while tier 1 is still
     * red, which the normal UI can never actually produce.
     */
    public ClaimResult forceClaim(Player player, String categoryId, int tierIndex) {
        MilestoneCategory category = content.get().get(categoryId);
        if (category == null || tierIndex < 0 || tierIndex >= category.tiers().size()) {
            return ClaimResult.UNKNOWN;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        String key = key(categoryId, tierIndex);
        if (achievementStore.getOrCreate(profile.getPlayerId()).getClaimedMilestoneKeys().contains(key)) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        for (int i = 0; i < tierIndex; i++) {
            achievementStore.getOrCreate(profile.getPlayerId()).getClaimedMilestoneKeys().add(key(categoryId, i));
        }
        long goal = category.tiers().get(tierIndex).goal();
        if (progressOf(profile, categoryId) < goal) {
            achievementStore.getOrCreate(profile.getPlayerId()).getMilestoneProgress().put(categoryId, goal);
        }
        store.save(player.getUniqueId());
        achievementStore.save(player.getUniqueId());
        return claim(player, categoryId, tierIndex);
    }
}
