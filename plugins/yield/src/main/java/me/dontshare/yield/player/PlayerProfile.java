package me.dontshare.yield.player;

import me.dontshare.yield.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

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
}
