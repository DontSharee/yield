package me.dontshare.yieldtools.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/**
 * How far down the tool path a player has bought. The path is linear, so
 * one number says everything: every tool up to and including
 * {@code ownedIndex} is theirs, and the next one is the only one they can
 * buy. -1 means none yet. Requires a public no-arg constructor for the
 * MongoDB POJO codec.
 */
public final class ToolProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private int ownedIndex = -1;

    public ToolProfile() {
    }

    public ToolProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public int getOwnedIndex() {
        return ownedIndex;
    }

    public void setOwnedIndex(int ownedIndex) {
        this.ownedIndex = ownedIndex;
    }
}
