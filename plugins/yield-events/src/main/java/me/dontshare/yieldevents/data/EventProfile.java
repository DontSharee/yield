package me.dontshare.yieldevents.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This plugin's own slice of a player's data - how much of each event's
 * currency they are holding.
 * <p>
 * Holds four things per event: the currency balance, quest progress,
 * which quests have already paid out, and what has been bought from the
 * shop. All keyed by event id and kept
 * forever rather than wiped when an event ends.
 * Leftover currency is worth nothing until next October, which is the point: a
 * player who grinds the last day of an event and cannot spend it all has
 * something waiting for them a year later, and nobody has to be told their
 * currency expired. Requires a public no-arg constructor for the MongoDB
 * POJO codec.
 */
public final class EventProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private Map<String, Long> balances = new HashMap<>();
    /** Quest progress, keyed "<eventId>:<GOAL>" so one event's counters never read another's. */
    private Map<String, Long> questProgress = new HashMap<>();
    /** Quests already paid out, keyed "<eventId>:<questId>" - a quest pays once, however many times it is re-completed. */
    private Set<String> claimedQuests = new HashSet<>();
    /**
     * How many of each limited shop entry has been bought, keyed
     * "&lt;eventId&gt;:&lt;entryId&gt;".
     * <p>
     * Kept forever like everything else here, which is what a limit of 1
     * has to mean: a pity buy a player could take again next October would
     * not be one.
     */
    private Map<String, Integer> shopPurchases = new HashMap<>();

    public EventProfile() {
    }

    public EventProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public Map<String, Long> getBalances() {
        return balances;
    }

    public void setBalances(Map<String, Long> balances) {
        this.balances = balances;
    }

    public long balance(String eventId) {
        return balances.getOrDefault(eventId, 0L);
    }

    public void add(String eventId, long amount) {
        balances.merge(eventId, amount, Long::sum);
    }

    public Map<String, Long> getQuestProgress() {
        return questProgress;
    }

    public void setQuestProgress(Map<String, Long> questProgress) {
        this.questProgress = questProgress;
    }

    public Set<String> getClaimedQuests() {
        return claimedQuests;
    }

    public void setClaimedQuests(Set<String> claimedQuests) {
        this.claimedQuests = claimedQuests;
    }

    public long progress(String eventId, EventQuest.Goal goal) {
        return questProgress.getOrDefault(eventId + ":" + goal.name(), 0L);
    }

    public void addProgress(String eventId, EventQuest.Goal goal, long amount) {
        questProgress.merge(eventId + ":" + goal.name(), amount, Long::sum);
    }

    public boolean hasClaimed(String eventId, String questId) {
        return claimedQuests.contains(eventId + ":" + questId);
    }

    public void markClaimed(String eventId, String questId) {
        claimedQuests.add(eventId + ":" + questId);
    }

    public Map<String, Integer> getShopPurchases() {
        return shopPurchases;
    }

    public void setShopPurchases(Map<String, Integer> shopPurchases) {
        this.shopPurchases = shopPurchases;
    }

    public int bought(String eventId, String entryId) {
        return shopPurchases.getOrDefault(eventId + ":" + entryId, 0);
    }

    public void recordPurchase(String eventId, String entryId, int amount) {
        shopPurchases.merge(eventId + ":" + entryId, amount, Integer::sum);
    }

    /** Takes {@code amount} if it is there, and reports whether it was. */
    public boolean take(String eventId, long amount) {
        long held = balance(eventId);
        if (held < amount) {
            return false;
        }
        balances.put(eventId, held - amount);
        return true;
    }
}
