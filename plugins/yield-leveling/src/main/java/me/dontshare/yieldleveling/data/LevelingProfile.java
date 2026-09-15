package me.dontshare.yieldleveling.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/**
 * This plugin's own slice of a player's data - whole-player level and XP,
 * which nothing outside yield-leveling reads.
 * <p>
 * Requires a public no-arg constructor for the MongoDB POJO codec.
 */
public final class LevelingProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private int level = 1;
    private long xp;

    public LevelingProfile() {
    }

    public LevelingProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public long getXp() {
        return xp;
    }

    public void setXp(long xp) {
        this.xp = xp;
    }
}
