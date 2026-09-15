package me.dontshare.yieldranks.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.UUID;

/**
 * This plugin's own slice of a player's data - which purchased donor rank
 * they hold, if any.
 * <p>
 * Requires a public no-arg constructor for the MongoDB POJO codec.
 */
public final class RankProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    /** Purchased rank id ("vip"/"celestial"), or null. Distinct from the unrelated prestige rank on the pack profile. */
    private String donorRankId;

    public RankProfile() {
    }

    public RankProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public String getDonorRankId() {
        return donorRankId;
    }

    public void setDonorRankId(String donorRankId) {
        this.donorRankId = donorRankId;
    }
}
