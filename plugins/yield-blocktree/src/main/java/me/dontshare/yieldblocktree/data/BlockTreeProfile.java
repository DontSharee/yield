package me.dontshare.yieldblocktree.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This plugin's own slice of a player's data - how much of each block they
 * have broken, and which tiers they have already claimed.
 * <p>
 * Progress is keyed by Bukkit {@code Material} name; claimed tiers use a
 * "MATERIAL:tierIndex" composite key, since several tiers can be complete
 * but unclaimed at once. Requires a public no-arg constructor for the
 * MongoDB POJO codec.
 */
public final class BlockTreeProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private Map<String, Long> progress = new HashMap<>();
    private Set<String> claimedTiers = new HashSet<>();

    public BlockTreeProfile() {
    }

    public BlockTreeProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public Map<String, Long> getProgress() {
        return progress;
    }

    public void setProgress(Map<String, Long> progress) {
        this.progress = progress;
    }

    public Set<String> getClaimedTiers() {
        return claimedTiers;
    }

    public void setClaimedTiers(Set<String> claimedTiers) {
        this.claimedTiers = claimedTiers;
    }
}
