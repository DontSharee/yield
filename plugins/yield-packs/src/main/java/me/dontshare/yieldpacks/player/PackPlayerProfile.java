package me.dontshare.yieldpacks.player;

import me.dontshare.yieldcore.database.PlayerRecord;
import me.dontshare.yieldpacks.display.PetVisibility;
import org.bson.codecs.pojo.annotations.BsonId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player persisted state for the pack-opening game: currency, owned
 * pets (the "bag"), currently equipped pet ids, per-pack collection
 * progress, and when each pet was last obtained (for "Newest to Oldest"
 * sorting). Requires a public no-arg constructor for the MongoDB POJO codec.
 */
public final class PackPlayerProfile implements PlayerRecord {

    @BsonId
    private UUID playerId;
    private long coins;
    private long gems;
    private int rebirths;
    private boolean autoOpenEnabled;
    private boolean rollAnimationEnabled = true;
    private PetVisibility petVisibility = PetVisibility.ALL;
    private Map<String, Integer> ownedItems = new HashMap<>();
    private List<String> equippedItemIds = new ArrayList<>();
    private Map<String, Set<String>> packCollectionProgress = new HashMap<>();
    private Map<String, Long> lastObtainedAt = new HashMap<>();

    public PackPlayerProfile() {
    }

    public PackPlayerProfile(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public long getCoins() {
        return coins;
    }

    public void setCoins(long coins) {
        this.coins = coins;
    }

    public long getGems() {
        return gems;
    }

    public void setGems(long gems) {
        this.gems = gems;
    }

    public int getRebirths() {
        return rebirths;
    }

    public void setRebirths(int rebirths) {
        this.rebirths = rebirths;
    }

    public boolean isAutoOpenEnabled() {
        return autoOpenEnabled;
    }

    public void setAutoOpenEnabled(boolean autoOpenEnabled) {
        this.autoOpenEnabled = autoOpenEnabled;
    }

    /** Whether opening a pack shows the Title/sound roll reveal - toggled with /rollanimation. */
    public boolean isRollAnimationEnabled() {
        return rollAnimationEnabled;
    }

    public void setRollAnimationEnabled(boolean rollAnimationEnabled) {
        this.rollAnimationEnabled = rollAnimationEnabled;
    }

    /** What this player personally sees of equipped-pet displays - toggled with /petvisibility. */
    public PetVisibility getPetVisibility() {
        return petVisibility;
    }

    public void setPetVisibility(PetVisibility petVisibility) {
        this.petVisibility = petVisibility;
    }

    public Map<String, Integer> getOwnedItems() {
        return ownedItems;
    }

    public void setOwnedItems(Map<String, Integer> ownedItems) {
        this.ownedItems = ownedItems;
    }

    public List<String> getEquippedItemIds() {
        return equippedItemIds;
    }

    public void setEquippedItemIds(List<String> equippedItemIds) {
        this.equippedItemIds = equippedItemIds;
    }

    public Map<String, Set<String>> getPackCollectionProgress() {
        return packCollectionProgress;
    }

    public void setPackCollectionProgress(Map<String, Set<String>> packCollectionProgress) {
        this.packCollectionProgress = packCollectionProgress;
    }

    public Map<String, Long> getLastObtainedAt() {
        return lastObtainedAt;
    }

    public void setLastObtainedAt(Map<String, Long> lastObtainedAt) {
        this.lastObtainedAt = lastObtainedAt;
    }

    /** Adds one to this pet's owned count, tracks it in the pack's collection progress, and stamps it as just-obtained. */
    public void addOwnedItem(String packId, String itemId) {
        ownedItems.merge(itemId, 1, Integer::sum);
        packCollectionProgress.computeIfAbsent(packId, ignored -> new HashSet<>()).add(itemId);
        lastObtainedAt.put(itemId, System.currentTimeMillis());
    }
}
