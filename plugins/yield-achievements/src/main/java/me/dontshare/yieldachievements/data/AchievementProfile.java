package me.dontshare.yieldachievements.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This plugin's own slice of a player's data - achievements, milestones and
 * currently-active potion effects.
 * <p>
 * Milestone progress is one counter per category, shared across that
 * category's tiers, while claims use a "categoryId:tierIndex" composite key
 * since progress can jump past several thresholds at once. Potion effects
 * are keyed "STAT_MULTIPLIER" and hold an absolute expiry, so they keep
 * counting down correctly across a relog. Requires a public no-arg
 * constructor for the MongoDB POJO codec.
 */
public final class AchievementProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private Map<String, Long> achievementProgress = new HashMap<>();
    private Set<String> claimedAchievementIds = new HashSet<>();
    private Map<String, Long> milestoneProgress = new HashMap<>();
    private Set<String> claimedMilestoneKeys = new HashSet<>();
    private Map<String, Long> activePotionExpiryMillis = new HashMap<>();

    public AchievementProfile() {
    }

    public AchievementProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public Map<String, Long> getAchievementProgress() {
        return achievementProgress;
    }

    public void setAchievementProgress(Map<String, Long> achievementProgress) {
        this.achievementProgress = achievementProgress;
    }

    public Set<String> getClaimedAchievementIds() {
        return claimedAchievementIds;
    }

    public void setClaimedAchievementIds(Set<String> claimedAchievementIds) {
        this.claimedAchievementIds = claimedAchievementIds;
    }

    public Map<String, Long> getMilestoneProgress() {
        return milestoneProgress;
    }

    public void setMilestoneProgress(Map<String, Long> milestoneProgress) {
        this.milestoneProgress = milestoneProgress;
    }

    public Set<String> getClaimedMilestoneKeys() {
        return claimedMilestoneKeys;
    }

    public void setClaimedMilestoneKeys(Set<String> claimedMilestoneKeys) {
        this.claimedMilestoneKeys = claimedMilestoneKeys;
    }

    public Map<String, Long> getActivePotionExpiryMillis() {
        return activePotionExpiryMillis;
    }

    public void setActivePotionExpiryMillis(Map<String, Long> activePotionExpiryMillis) {
        this.activePotionExpiryMillis = activePotionExpiryMillis;
    }
}
