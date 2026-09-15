package me.dontshare.yieldmining.data;

import me.dontshare.yieldcore.database.PlayerRecord;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * This plugin's own slice of a player's data - pickaxe enchants and the Ore
 * Bag.
 * <p>
 * Enchant levels and mastery are keyed by the enchant's config id, with a
 * missing entry meaning zero; {@code enchantDisabled} is a player-toggled
 * "don't roll this one" preference, not a lock. Ore Bag entries are encoded
 * as "MATERIAL:multiplier:tierId" rather than a typed object, so this record
 * doesn't need to know yield-mining's own tier types to round-trip.
 * <p>
 * Requires a public no-arg constructor for the MongoDB POJO codec.
 */
public final class MiningProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private Map<String, Integer> enchantLevels = new HashMap<>();
    private Map<String, Integer> enchantMastery = new HashMap<>();
    private Set<String> enchantDisabled = new HashSet<>();
    private Map<String, String> oreBagEntries = new LinkedHashMap<>();
    private boolean oreBagNotificationsEnabled = true;
    private Set<String> discoveredOreMaterials = new HashSet<>();

    public MiningProfile() {
    }

    public MiningProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public Map<String, Integer> getEnchantLevels() {
        return enchantLevels;
    }

    public void setEnchantLevels(Map<String, Integer> enchantLevels) {
        this.enchantLevels = enchantLevels;
    }

    public Map<String, Integer> getEnchantMastery() {
        return enchantMastery;
    }

    public void setEnchantMastery(Map<String, Integer> enchantMastery) {
        this.enchantMastery = enchantMastery;
    }

    public Set<String> getEnchantDisabled() {
        return enchantDisabled;
    }

    public void setEnchantDisabled(Set<String> enchantDisabled) {
        this.enchantDisabled = enchantDisabled;
    }

    public Map<String, String> getOreBagEntries() {
        return oreBagEntries;
    }

    public void setOreBagEntries(Map<String, String> oreBagEntries) {
        this.oreBagEntries = oreBagEntries;
    }

    public boolean isOreBagNotificationsEnabled() {
        return oreBagNotificationsEnabled;
    }

    public void setOreBagNotificationsEnabled(boolean oreBagNotificationsEnabled) {
        this.oreBagNotificationsEnabled = oreBagNotificationsEnabled;
    }

    public Set<String> getDiscoveredOreMaterials() {
        return discoveredOreMaterials;
    }

    public void setDiscoveredOreMaterials(Set<String> discoveredOreMaterials) {
        this.discoveredOreMaterials = discoveredOreMaterials;
    }
}
