package me.dontshare.yieldcore.player;

import me.dontshare.yieldcore.database.PlayerRecord;
import me.dontshare.yieldcore.home.HomePoint;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Core per-player profile. Requires a public no-arg constructor for the
 * MongoDB POJO codec to deserialize with.
 */
public final class PlayerProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private String username;
    private long firstJoined;
    private long lastSeen;
    // Interactive tutorial progress - lives here rather than on any single
    // feature plugin's own profile since the tutorial spans several of them
    // (packs, zones) and this is the one player-record yield-core itself
    // owns independently of any of them.
    private int tutorialStep;
    private boolean tutorialSkipped;
    /** How far into the CURRENT step's goal (see TutorialStep#goal) the player is - reset to 0 whenever tutorialStep advances. */
    private int tutorialStepProgress;
    // Named home locations - see yield-core's HomeService.
    private Map<String, HomePoint> homes = new HashMap<>();

    public PlayerProfile() {
    }

    public PlayerProfile(UUID playerId, String username) {
        this.playerId = playerId;
        this.username = username;
        this.firstJoined = System.currentTimeMillis();
        this.lastSeen = firstJoined;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public long getFirstJoined() {
        return firstJoined;
    }

    public void setFirstJoined(long firstJoined) {
        this.firstJoined = firstJoined;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    public int getTutorialStep() {
        return tutorialStep;
    }

    public void setTutorialStep(int tutorialStep) {
        this.tutorialStep = tutorialStep;
    }

    public boolean isTutorialSkipped() {
        return tutorialSkipped;
    }

    public void setTutorialSkipped(boolean tutorialSkipped) {
        this.tutorialSkipped = tutorialSkipped;
    }

    public int getTutorialStepProgress() {
        return tutorialStepProgress;
    }

    public void setTutorialStepProgress(int tutorialStepProgress) {
        this.tutorialStepProgress = tutorialStepProgress;
    }

    public Map<String, HomePoint> getHomes() {
        return homes;
    }

    public void setHomes(Map<String, HomePoint> homes) {
        this.homes = homes;
    }
}
