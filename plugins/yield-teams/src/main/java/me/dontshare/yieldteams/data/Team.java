package me.dontshare.yieldteams.data;

import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A player team - its own top-level MongoDB document (a separate "teams"
 * collection, not a sub-field on a player's document like every other
 * plugin's data, since a team isn't owned by any single player). Requires a
 * public no-arg constructor for the MongoDB POJO codec.
 */
public final class Team {

    @BsonId
    private UUID id;
    private String name;
    private UUID leaderId;
    private List<UUID> memberIds = new ArrayList<>();
    private BigInteger trophyBalance = BigInteger.ZERO;
    private Map<String, Integer> upgradeLevels = new HashMap<>();
    private long createdAt;

    public Team() {
    }

    public Team(UUID id, String name, UUID leaderId) {
        this.id = id;
        this.name = name;
        this.leaderId = leaderId;
        this.memberIds.add(leaderId);
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(UUID leaderId) {
        this.leaderId = leaderId;
    }

    public List<UUID> getMemberIds() {
        return memberIds;
    }

    public void setMemberIds(List<UUID> memberIds) {
        this.memberIds = memberIds;
    }

    public BigInteger getTrophyBalance() {
        return trophyBalance;
    }

    public void setTrophyBalance(BigInteger trophyBalance) {
        this.trophyBalance = trophyBalance;
    }

    public Map<String, Integer> getUpgradeLevels() {
        return upgradeLevels;
    }

    public void setUpgradeLevels(Map<String, Integer> upgradeLevels) {
        this.upgradeLevels = upgradeLevels;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
