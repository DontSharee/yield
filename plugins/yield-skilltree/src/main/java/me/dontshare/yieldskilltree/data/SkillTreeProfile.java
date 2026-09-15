package me.dontshare.yieldskilltree.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * This plugin's own slice of a player's data - node id to owned level, one
 * flat namespace shared by every tree (node ids are unique tree-wide).
 * <p>
 * Requires a public no-arg constructor for the MongoDB POJO codec.
 */
public final class SkillTreeProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private Map<String, Integer> levels = new HashMap<>();

    public SkillTreeProfile() {
    }

    public SkillTreeProfile(UUID playerId) {
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
