package me.dontshare.yieldupgrades.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * This plugin's own slice of a player's data - upgrade type id to global
 * level, shared across every station of that type.
 * <p>
 * Requires a public no-arg constructor for the MongoDB POJO codec.
 */
public final class UpgradeProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private Map<String, Integer> levels = new HashMap<>();

    public UpgradeProfile() {
    }

    public UpgradeProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public Map<String, Integer> getLevels() {
        return levels;
    }

    public void setLevels(Map<String, Integer> levels) {
        this.levels = levels;
    }
}
