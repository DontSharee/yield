package me.dontshare.yieldanalytics.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the analytics keep per player that no gameplay record already does:
 * when they first came and last left, how long they have played, and when
 * they reached each milestone - so "how long until players reach zone 3" and
 * "where do new players stop" have answers.
 * <p>
 * Stored as the {@code analytics} field of the shared player document.
 */
public final class AnalyticsProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private long firstSeen;
    private long lastSeen;
    private long playtimeMs;
    private int sessions;
    private long longestSessionMs;
    private long tutorialStartedAt;
    private long tutorialFinishedAt;
    /** "", "completed" or "skipped". */
    private String tutorialOutcome = "";
    /** Furthest tutorial step index reached. */
    private int tutorialStepReached;
    /** Zone id to when the player first stood in (or unlocked) it. */
    private Map<String, Long> zoneReachedAt = new HashMap<>();

    public AnalyticsProfile() {
    }

    public AnalyticsProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public long getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(long firstSeen) {
        this.firstSeen = firstSeen;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public long getPlaytimeMs() {
        return playtimeMs;
    }

    public void setPlaytimeMs(long playtimeMs) {
        this.playtimeMs = playtimeMs;
    }

    public int getSessions() {
        return sessions;
    }

    public void setSessions(int sessions) {
        this.sessions = sessions;
    }

    public long getLongestSessionMs() {
        return longestSessionMs;
    }

    public void setLongestSessionMs(long longestSessionMs) {
        this.longestSessionMs = longestSessionMs;
    }

    public long getTutorialStartedAt() {
        return tutorialStartedAt;
    }

    public void setTutorialStartedAt(long tutorialStartedAt) {
        this.tutorialStartedAt = tutorialStartedAt;
    }

    public long getTutorialFinishedAt() {
        return tutorialFinishedAt;
    }

    public void setTutorialFinishedAt(long tutorialFinishedAt) {
        this.tutorialFinishedAt = tutorialFinishedAt;
    }

    public String getTutorialOutcome() {
        return tutorialOutcome;
    }

    public void setTutorialOutcome(String tutorialOutcome) {
        this.tutorialOutcome = tutorialOutcome != null ? tutorialOutcome : "";
    }

    public int getTutorialStepReached() {
        return tutorialStepReached;
    }

    public void setTutorialStepReached(int tutorialStepReached) {
        this.tutorialStepReached = tutorialStepReached;
    }

    public Map<String, Long> getZoneReachedAt() {
        return zoneReachedAt;
    }

    public void setZoneReachedAt(Map<String, Long> zoneReachedAt) {
        this.zoneReachedAt = zoneReachedAt != null ? zoneReachedAt : new HashMap<>();
    }
}
