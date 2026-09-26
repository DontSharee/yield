package me.dontshare.yieldquests.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This plugin's own slice of a player's data - today's daily quests and the
 * login streak.
 * <p>
 * Progress, claims and the chosen difficulty per category all reset together
 * on a new quest day, tracked by {@code lastResetEpochDay}. Requires a public
 * no-arg constructor for the MongoDB POJO codec.
 */
public final class QuestProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private long lastResetEpochDay = -1;
    private Map<String, Integer> progress = new HashMap<>();
    private Set<String> claimedIds = new HashSet<>();
    /** Category id -> chosen difficulty ("EASY"/"MEDIUM"/"HARD") for today, locked in once picked. */
    private Map<String, String> selectedDifficultyByCategory = new HashMap<>();
    /** LocalDate#toEpochDay of the last day a login was credited (never the same day twice). */
    private long lastLoginEpochDay;
    private int loginStreak;
    /** The 3 Rank Quest template ids currently active for this player (see RankQuestService) - never daily-reset, only rerolled once all 3 are completed. */
    private List<String> activeRankQuestIds = new ArrayList<>();
    /** Rank Quest template id -> progress toward its goal, only for the currently active 3. */
    private Map<String, Long> rankQuestProgress = new HashMap<>();
    /** Subset of activeRankQuestIds already completed this cycle - cleared together with activeRankQuestIds on reroll. */
    private Set<String> completedRankQuestIds = new HashSet<>();
    /** The day (epoch day, server time) the gift fields below are for - see PresentsService. */
    private long giftEpochDay = -1;
    /** Time played on {@link #giftEpochDay} in sessions that have ended. */
    private long giftPlayMs;
    /** Which of that day's gifts are opened, by position. */
    private Set<Integer> giftsClaimed = new HashSet<>();

    public QuestProfile() {
    }

    public QuestProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public long getLastResetEpochDay() {
        return lastResetEpochDay;
    }

    public void setLastResetEpochDay(long lastResetEpochDay) {
        this.lastResetEpochDay = lastResetEpochDay;
    }

    public Map<String, Integer> getProgress() {
        return progress;
    }

    public void setProgress(Map<String, Integer> progress) {
        this.progress = progress;
    }

    public Set<String> getClaimedIds() {
        return claimedIds;
    }

    public void setClaimedIds(Set<String> claimedIds) {
        this.claimedIds = claimedIds;
    }

    public Map<String, String> getSelectedDifficultyByCategory() {
        return selectedDifficultyByCategory;
    }

    public void setSelectedDifficultyByCategory(Map<String, String> selectedDifficultyByCategory) {
        this.selectedDifficultyByCategory = selectedDifficultyByCategory;
    }

    public long getLastLoginEpochDay() {
        return lastLoginEpochDay;
    }

    public void setLastLoginEpochDay(long lastLoginEpochDay) {
        this.lastLoginEpochDay = lastLoginEpochDay;
    }

    public int getLoginStreak() {
        return loginStreak;
    }

    public void setLoginStreak(int loginStreak) {
        this.loginStreak = loginStreak;
    }

    public List<String> getActiveRankQuestIds() {
        return activeRankQuestIds;
    }

    public void setActiveRankQuestIds(List<String> activeRankQuestIds) {
        this.activeRankQuestIds = activeRankQuestIds;
    }

    public Map<String, Long> getRankQuestProgress() {
        return rankQuestProgress;
    }

    public void setRankQuestProgress(Map<String, Long> rankQuestProgress) {
        this.rankQuestProgress = rankQuestProgress;
    }

    public Set<String> getCompletedRankQuestIds() {
        return completedRankQuestIds;
    }

    public void setCompletedRankQuestIds(Set<String> completedRankQuestIds) {
        this.completedRankQuestIds = completedRankQuestIds;
    }

    public long getGiftEpochDay() {
        return giftEpochDay;
    }

    public void setGiftEpochDay(long giftEpochDay) {
        this.giftEpochDay = giftEpochDay;
    }

    public long getGiftPlayMs() {
        return giftPlayMs;
    }

    public void setGiftPlayMs(long giftPlayMs) {
        this.giftPlayMs = giftPlayMs;
    }

    public Set<Integer> getGiftsClaimed() {
        return giftsClaimed;
    }

    public void setGiftsClaimed(Set<Integer> giftsClaimed) {
        this.giftsClaimed = giftsClaimed != null ? giftsClaimed : new HashSet<>();
    }
}
