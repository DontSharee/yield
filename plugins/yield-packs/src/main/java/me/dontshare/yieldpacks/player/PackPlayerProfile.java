package me.dontshare.yieldpacks.player;

import me.dontshare.yieldcore.database.PlayerRecord;
import me.dontshare.yieldpacks.display.PetVisibility;
import me.dontshare.yieldpacks.pet.PetInstance;
import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.codecs.pojo.annotations.BsonProperty;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private BigInteger coins = BigInteger.ZERO;
    // BSON key stays "gems" (the currency's Gems -> Diamonds rename is
    // display/code-only) so every already-saved player document's balance
    // keeps mapping to this field under the POJO codec's automatic,
    // field-name-based mapping - without this, renaming the Java field name
    // alone would silently reset every player's balance to zero on next load.
    @BsonProperty("gems")
    private BigInteger diamonds = BigInteger.ZERO;
    private int rebirths;
    /** Earned from completing Rank Quests (see yield-quests' RankQuestService) - accumulates forever. Rank itself is never stored, always derived fresh from this via RankService#rankOf, same "never cache a derived value" shape as MasteryService#levelOf. */
    private long stars;
    /** Sequential claim watermark for RankService's per-rank reward - the highest rank whose one-time coin/diamond reward has already been claimed. */
    private int claimedRank;
    private boolean autoOpenEnabled;
    private boolean rollAnimationEnabled = true;
    /** Whether a rare pull gets the shake-and-pop-to-screen moment when it hatches - /settings. */
    private boolean rarePetAnimationEnabled = true;
    private PetVisibility petVisibility = PetVisibility.ALL;
    /** Every pet this player has ever obtained - equipped or sitting in storage, this IS the Bag's whole inventory. Each is a real, permanent instance with its own level/XP (see {@link PetInstance}) - never lost on unequip. */
    private List<PetInstance> pets = new ArrayList<>();
    /** Ordered instance ids from {@link #pets} currently equipped - equip-slot order, same role {@code equippedItemIds} used to play before the per-instance migration. */
    private List<UUID> equippedPetIds = new ArrayList<>();
    private SendMode sendMode = SendMode.MANUAL;
    /**
     * True once the player has explicitly switched auto-attack OFF. A
     * level-10 pet's AUTO_ATTACK milestone makes it fight on its own
     * regardless of send mode - that is how a free player gets any
     * auto-attack at all - but it must not override a player who turned
     * auto-attack off: before this flag existed, the switch "didn't work"
     * the moment a squad hit level 10.
     */
    private boolean autoAttackOff;
    private AttackMode attackMode = AttackMode.SINGLE;
    private Map<String, Set<String>> packCollectionProgress = new HashMap<>();
    private Map<String, Long> lastObtainedAt = new HashMap<>();
    private Map<String, Integer> storedPacks = new HashMap<>();
    private int autoHatchAmount = 1;
    /** Virtual crate-key counts, keyed by crate id (see yield-spawnnpcs' CrateDefinition/CrateService) - found via ore cube kills, spent one at a time by physically smacking that crate's own station. Never a real inventory item. */
    private Map<String, Integer> crateKeys = new HashMap<>();
    private String activePackId;
    private long lastStockCycleId = -1;
    private Map<String, Integer> stockPurchasedThisCycle = new HashMap<>();
    private long rollCount;
    // The rarest single pull this player has ever landed, as the "1 in N" the
    // reveal showed them - so a 1-in-300,000 Huge stays on their record
    // forever rather than being a message that scrolled past. Set once, never
    // decreases, and never recomputed from current odds: the whole point is
    // that it is the number they actually beat at the time, with the luck
    // they actually had. See PackRollService#rollInPlace.
    private long bestLuckOneIn;
    private String bestLuckItemId;
    private AutoTargetMode autoTargetMode = AutoTargetMode.CLOSEST;
    private int prestiges;
    private BigInteger prestigePoints = BigInteger.ZERO;
    private boolean autoFuseEnabled;
    /** True once this player has been shown (or auto-defaulted through) the first-join starter pet choice - see StarterPetService. */
    private boolean starterPetGranted;
    private long lifetimeCubeKills;
    private BigInteger lifetimeCoinsEarned = BigInteger.ZERO;
    /** Lifetime damage dealt to world bosses (see yield-zones' WorldBossService) - drives the leaderboard system, same as lifetimeCubeKills. */
    private long lifetimeBossDamage;
    // Zone ids this player has purchased/unlocked - see ZoneUnlockCost/
    // ZoneLockService (yield-zones). A zone with a free ZoneUnlockCost is
    // never checked against this set at all, so it doesn't need an entry
    // here to be accessible.
    private Set<String> unlockedZoneIds = new HashSet<>();
    // The team this player currently belongs to, or null - the actual Team
    // document (name/members/trophy balance/upgrades) lives in its own
    // yield-teams collection, keyed by this id. See yield-teams' TeamStore.
    private UUID teamId;
    private BigInteger credits = BigInteger.ZERO;
    /** A permanent, admin-granted luck bonus (see /admin stats) - additive, same slot shape as every other LuckService contributor. */
    private double adminLuckBonus;
    // Permanent shard bonuses (see me.dontshare.yieldpacks.shard.ShardService)
    // - each right-click-consumed Shard nudges its own stat up forever, never
    // resets, never decays. Additive percentages, same "1.0 + total" shape
    // every other multiplier in this codebase already uses.
    private double shardDamageBonus;
    private double shardCoinBonus;
    // Same "keep the BSON key, rename the Java side" reasoning as diamonds
    // above - this is a smaller, secondary bonus rather than the whole
    // balance, but the same silent-reset-to-zero risk applies.
    @BsonProperty("shardGemBonus")
    private double shardDiamondBonus;
    private double shardLuckBonus;
    private double shardAttackSpeedBonus;
    private double shardCritChanceBonus;
    private List<String> enchantSlots = newEmptyEnchantSlots();
    /**
     * The Enchant Market hour {@link #enchantMarketBought} belongs to - see
     * yield-packs' EnchantMarketService. Offers are regenerated from the
     * player and the hour rather than stored, so this is the only market
     * state there is; a stale hour simply means nothing is bought yet.
     */
    private long enchantMarketHour;
    private List<Integer> enchantMarketBought = new ArrayList<>();
    // Mastery (see yield-packs' MasteryService) - accumulated XP per
    // MasteryType#name(), always-growing, never spent - level is derived
    // fresh from this each time, same philosophy as PlayerLevelingService.
    private Map<String, Long> masteryXp = new HashMap<>();

    private static List<String> newEmptyEnchantSlots() {
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            slots.add("");
        }
        return slots;
    }

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

    public BigInteger getCoins() {
        return coins;
    }

    public void setCoins(BigInteger coins) {
        this.coins = coins;
    }

    public BigInteger getDiamonds() {
        return diamonds;
    }

    public void setDiamonds(BigInteger diamonds) {
        this.diamonds = diamonds;
    }

    public int getRebirths() {
        return rebirths;
    }

    public void setRebirths(int rebirths) {
        this.rebirths = rebirths;
    }

    public long getStars() {
        return stars;
    }

    public void setStars(long stars) {
        this.stars = stars;
    }

    public int getClaimedRank() {
        return claimedRank;
    }

    public void setClaimedRank(int claimedRank) {
        this.claimedRank = claimedRank;
    }

    /** Whether PackOpenService should keep hatching, at {@link #getAutoHatchAmount()} a time, while this player stands at an egg. */
    public boolean isAutoOpenEnabled() {
        return autoOpenEnabled;
    }

    public void setAutoOpenEnabled(boolean autoOpenEnabled) {
        this.autoOpenEnabled = autoOpenEnabled;
    }

    /**
     * How many eggs each auto-hatch tick hatches - the rung the player
     * picked in the hatch menu while auto-hatch was on.
     * <p>
     * Auto-hatch holds this amount exactly rather than hatching whatever is
     * affordable: a player who chose 5x wants five at a time, and quietly
     * dropping to one when they are briefly short would make the loop's
     * spend rate something they never asked for. A tick it cannot afford is
     * simply skipped.
     */
    public int getAutoHatchAmount() {
        return Math.max(1, autoHatchAmount);
    }

    public void setAutoHatchAmount(int autoHatchAmount) {
        this.autoHatchAmount = Math.max(1, autoHatchAmount);
    }

    /** Whether opening a pack shows the Title/sound roll reveal - toggled with /rollanimation. */
    public boolean isRollAnimationEnabled() {
        return rollAnimationEnabled;
    }

    public void setRollAnimationEnabled(boolean rollAnimationEnabled) {
        this.rollAnimationEnabled = rollAnimationEnabled;
    }

    public boolean isRarePetAnimationEnabled() {
        return rarePetAnimationEnabled;
    }

    public void setRarePetAnimationEnabled(boolean rarePetAnimationEnabled) {
        this.rarePetAnimationEnabled = rarePetAnimationEnabled;
    }

    /** What this player personally sees of equipped-pet displays - toggled with /petvisibility. */
    public PetVisibility getPetVisibility() {
        return petVisibility;
    }

    public void setPetVisibility(PetVisibility petVisibility) {
        this.petVisibility = petVisibility;
    }

    /**
     * Lookup index over {@link #pets}, rebuilt on demand.
     * <p>
     * Not persisted - it has no getter/setter, so the POJO codec never sees
     * it as a property. Invalidated by the mutators below; the size check in
     * {@link #petIndex()} is a backstop that also catches anything mutating
     * the list directly, which is why {@link #getPets()} staying writable
     * doesn't make this unsafe.
     */
    private transient Map<UUID, PetInstance> petsById;

    /**
     * The live list. Prefer {@link #addPet}/{@link #removePet}/
     * {@link #removePets}/{@link #clearPets} for changes - they keep
     * {@link #findPet} fast instead of forcing it to rebuild.
     */
    public List<PetInstance> getPets() {
        return pets;
    }

    public void setPets(List<PetInstance> pets) {
        this.pets = pets;
        this.petsById = null;
    }

    public void addPet(PetInstance pet) {
        pets.add(pet);
        petsById = null;
    }

    /** Removes the pet with this instance id, returning whether it was there. */
    public boolean removePet(UUID instanceId) {
        boolean removed = pets.removeIf(pet -> pet.getInstanceId().equals(instanceId));
        petsById = null;
        return removed;
    }

    public void removePets(Collection<PetInstance> toRemove) {
        pets.removeAll(toRemove);
        petsById = null;
    }

    public void clearPets() {
        pets.clear();
        petsById = null;
    }

    private Map<UUID, PetInstance> petIndex() {
        Map<UUID, PetInstance> index = petsById;
        if (index != null && index.size() == pets.size()) {
            return index;
        }
        index = new HashMap<>(Math.max(16, pets.size() * 2));
        for (PetInstance pet : pets) {
            index.put(pet.getInstanceId(), pet);
        }
        petsById = index;
        return index;
    }

    public List<UUID> getEquippedPetIds() {
        return equippedPetIds;
    }

    public void setEquippedPetIds(List<UUID> equippedPetIds) {
        this.equippedPetIds = equippedPetIds;
    }

    /**
     * Looks up one specific pet by its permanent instance id - empty if it's
     * been deleted/fused away.
     * <p>
     * Index-backed rather than a scan: the combat loop resolves every
     * equipped pet this way several times a second, so a linear pass here
     * made each of those cost the whole bag.
     */
    public Optional<PetInstance> findPet(UUID instanceId) {
        return Optional.ofNullable(petIndex().get(instanceId));
    }

    /** Whether {@code instanceId} is one of this player's currently equipped pets. */
    public boolean isEquipped(UUID instanceId) {
        return equippedPetIds.contains(instanceId);
    }

    /** Whether equipped pets fight on their own (AUTO) or only when sent (MANUAL, default) - set via /sendmode. */
    public SendMode getSendMode() {
        return sendMode;
    }

    public void setSendMode(SendMode sendMode) {
        this.sendMode = sendMode;
    }

    public boolean isAutoAttackOff() {
        return autoAttackOff;
    }

    public void setAutoAttackOff(boolean autoAttackOff) {
        this.autoAttackOff = autoAttackOff;
    }

    /**
     * The one switch: ON is full Auto Send, OFF is nothing fighting on its
     * own - milestone pets included - until the player clicks a cube.
     */
    public void setAutoAttack(boolean on) {
        this.sendMode = on ? SendMode.AUTO : SendMode.MANUAL;
        this.autoAttackOff = !on;
    }

    /** Only consulted while {@link #getSendMode()} is MANUAL - toggled from the Settings GUI, not a command. */
    public AttackMode getAttackMode() {
        return attackMode;
    }

    public void setAttackMode(AttackMode attackMode) {
        this.attackMode = attackMode;
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

    /** Grants a brand-new, level-1 baseline instance of this pet, tracks it in the pack's collection progress, and stamps it as just-obtained. Returns the new instance so the caller can auto-equip it. */
    public PetInstance addOwnedItem(String packId, String itemId) {
        PetInstance pet = new PetInstance(UUID.randomUUID(), itemId);
        addPet(pet);
        packCollectionProgress.computeIfAbsent(packId, ignored -> new HashSet<>()).add(itemId);
        lastObtainedAt.put(itemId, System.currentTimeMillis());
        return pet;
    }

    /** Unopened pack inventory - packId -> how many of that pack this player owns but hasn't opened yet. */
    public Map<String, Integer> getStoredPacks() {
        return storedPacks;
    }

    /** Virtual crate-key counts - crateId -> how many of that crate's own Key this player currently has (see CrateService). */
    public Map<String, Integer> getCrateKeys() {
        return crateKeys;
    }

    public void setStoredPacks(Map<String, Integer> storedPacks) {
        this.storedPacks = storedPacks;
    }

    /** The pack currently selected for manual/auto opening (see /packstorage) - null if none selected yet. */
    public String getActivePackId() {
        return activePackId;
    }

    public void setActivePackId(String activePackId) {
        this.activePackId = activePackId;
    }

    /** Which shop rotation cycle {@link #getStockPurchasedThisCycle()} was last reset for - see ShopStockService. */
    public long getLastStockCycleId() {
        return lastStockCycleId;
    }

    public void setLastStockCycleId(long lastStockCycleId) {
        this.lastStockCycleId = lastStockCycleId;
    }

    /** How many of each pack this player has bought from the rotating shop so far this cycle. */
    public Map<String, Integer> getStockPurchasedThisCycle() {
        return stockPurchasedThisCycle;
    }

    public void setStockPurchasedThisCycle(Map<String, Integer> stockPurchasedThisCycle) {
        this.stockPurchasedThisCycle = stockPurchasedThisCycle;
    }

    /** Lifetime count of packs this player has opened - drives the pity system (see PityService). */
    /** The "1 in N" of the rarest pull this player has ever landed - 0 if they have never opened a pack. */
    public long getBestLuckOneIn() {
        return bestLuckOneIn;
    }

    public void setBestLuckOneIn(long bestLuckOneIn) {
        this.bestLuckOneIn = bestLuckOneIn;
    }

    /** The item id behind {@link #getBestLuckOneIn} - null until they land their first pull. */
    public String getBestLuckItemId() {
        return bestLuckItemId;
    }

    public void setBestLuckItemId(String bestLuckItemId) {
        this.bestLuckItemId = bestLuckItemId;
    }

    public long getRollCount() {
        return rollCount;
    }

    public void setRollCount(long rollCount) {
        this.rollCount = rollCount;
    }

    /** Only consulted while {@link #getSendMode()} is AUTO - which live cube idle pets pick on their own, set via /autotarget. */
    public AutoTargetMode getAutoTargetMode() {
        return autoTargetMode;
    }

    public void setAutoTargetMode(AutoTargetMode autoTargetMode) {
        this.autoTargetMode = autoTargetMode;
    }

    /** Lifetime count of times this player has Prestiged - see yield-skilltree's PrestigeService. */
    public int getPrestiges() {
        return prestiges;
    }

    public void setPrestiges(int prestiges) {
        this.prestiges = prestiges;
    }

    /** Currency spent exclusively in the Prestige Upgrades skill tree - granted by Prestiging. */
    public BigInteger getPrestigePoints() {
        return prestigePoints;
    }

    public void setPrestigePoints(BigInteger prestigePoints) {
        this.prestigePoints = prestigePoints;
    }

    /** Whether AutoFuseService should keep auto-fusing everything fusable for this player - requires the "yieldpacks.autofuse" permission to actually run, checked every tick, not just at toggle time. */
    public boolean isAutoFuseEnabled() {
        return autoFuseEnabled;
    }

    public void setAutoFuseEnabled(boolean autoFuseEnabled) {
        this.autoFuseEnabled = autoFuseEnabled;
    }

    public boolean isStarterPetGranted() {
        return starterPetGranted;
    }

    public void setStarterPetGranted(boolean starterPetGranted) {
        this.starterPetGranted = starterPetGranted;
    }

    /** Lifetime count of ore cubes killed - distinct from any current balance, never decreases. Drives the leaderboard system. */
    public long getLifetimeCubeKills() {
        return lifetimeCubeKills;
    }

    public void setLifetimeCubeKills(long lifetimeCubeKills) {
        this.lifetimeCubeKills = lifetimeCubeKills;
    }

    /** Lifetime coins earned from ore cube kills - distinct from {@link #getCoins()}, which can go down when spent. Drives the leaderboard system. */
    public BigInteger getLifetimeCoinsEarned() {
        return lifetimeCoinsEarned;
    }

    public void setLifetimeCoinsEarned(BigInteger lifetimeCoinsEarned) {
        this.lifetimeCoinsEarned = lifetimeCoinsEarned;
    }

    public long getLifetimeBossDamage() {
        return lifetimeBossDamage;
    }

    public void setLifetimeBossDamage(long lifetimeBossDamage) {
        this.lifetimeBossDamage = lifetimeBossDamage;
    }

    /** Equipped chat-color cosmetic id, or null for none - see yield-cosmetics. */
    /** Equipped nameplate cosmetic id, or null for none - see yield-cosmetics. */
    /** Equipped tag cosmetic id, or null for none - see yield-cosmetics. */
    /** Zone ids this player has purchased - see yield-zones' ZoneLockService. A free zone is never checked against this at all. */
    public Set<String> getUnlockedZoneIds() {
        return unlockedZoneIds;
    }

    public void setUnlockedZoneIds(Set<String> unlockedZoneIds) {
        this.unlockedZoneIds = unlockedZoneIds;
    }

    /** The team this player belongs to, or null - see yield-teams' TeamStore/TeamService. */
    public UUID getTeamId() {
        return teamId;
    }

    public void setTeamId(UUID teamId) {
        this.teamId = teamId;
    }

    public BigInteger getCredits() {
        return credits;
    }

    public void setCredits(BigInteger credits) {
        this.credits = credits;
    }

    public List<String> getEnchantSlots() {
        return enchantSlots;
    }

    public long getEnchantMarketHour() {
        return enchantMarketHour;
    }

    public void setEnchantMarketHour(long enchantMarketHour) {
        this.enchantMarketHour = enchantMarketHour;
    }

    public List<Integer> getEnchantMarketBought() {
        return enchantMarketBought;
    }

    public void setEnchantMarketBought(List<Integer> enchantMarketBought) {
        this.enchantMarketBought = enchantMarketBought == null ? new ArrayList<>() : enchantMarketBought;
    }

    public Map<String, Long> getMasteryXp() {
        return masteryXp;
    }

    public double getAdminLuckBonus() {
        return adminLuckBonus;
    }

    public void setAdminLuckBonus(double adminLuckBonus) {
        this.adminLuckBonus = adminLuckBonus;
    }

    public double getShardDamageBonus() {
        return shardDamageBonus;
    }

    public void setShardDamageBonus(double shardDamageBonus) {
        this.shardDamageBonus = shardDamageBonus;
    }

    public double getShardCoinBonus() {
        return shardCoinBonus;
    }

    public void setShardCoinBonus(double shardCoinBonus) {
        this.shardCoinBonus = shardCoinBonus;
    }

    public double getShardDiamondBonus() {
        return shardDiamondBonus;
    }

    public void setShardDiamondBonus(double shardDiamondBonus) {
        this.shardDiamondBonus = shardDiamondBonus;
    }

    public double getShardLuckBonus() {
        return shardLuckBonus;
    }

    public void setShardLuckBonus(double shardLuckBonus) {
        this.shardLuckBonus = shardLuckBonus;
    }

    public double getShardAttackSpeedBonus() {
        return shardAttackSpeedBonus;
    }

    public void setShardAttackSpeedBonus(double shardAttackSpeedBonus) {
        this.shardAttackSpeedBonus = shardAttackSpeedBonus;
    }

    public double getShardCritChanceBonus() {
        return shardCritChanceBonus;
    }

    public void setShardCritChanceBonus(double shardCritChanceBonus) {
        this.shardCritChanceBonus = shardCritChanceBonus;
    }

}
