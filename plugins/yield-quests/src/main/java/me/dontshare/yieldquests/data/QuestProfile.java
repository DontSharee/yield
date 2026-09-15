package me.dontshare.yieldquests.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.HashSet;
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
}
