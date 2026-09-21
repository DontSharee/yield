package me.dontshare.yieldevents.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * This plugin's own slice of a player's data - how much of each event's
 * currency they are holding.
 * <p>
 * Keyed by event id and kept forever rather than wiped when an event ends.
 * Leftover Candy is worth nothing until next October, which is the point: a
 * player who grinds the last day of an event and cannot spend it all has
 * something waiting for them a year later, and nobody has to be told their
 * currency expired. Requires a public no-arg constructor for the MongoDB
 * POJO codec.
 */
public final class EventProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private Map<String, Long> balances = new HashMap<>();

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
