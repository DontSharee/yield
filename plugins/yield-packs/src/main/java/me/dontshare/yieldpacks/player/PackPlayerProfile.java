package me.dontshare.yieldpacks.player;

import me.dontshare.yieldcore.database.PlayerRecord;
import me.dontshare.yieldpacks.display.PetVisibility;
import me.dontshare.yieldpacks.pet.PetInstance;
import org.bson.codecs.pojo.annotations.BsonId;

import java.math.BigInteger;
import java.util.ArrayList;
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
    private BigInteger gems = BigInteger.ZERO;
    private int rebirths;
    /** Purchased with gems (see RankService) - a permanent, ever-climbing prestige track separate from rebirths, boosting gem income. Rendered as a Roman numeral (Formatting#toRoman) everywhere it's shown. */
    private int rank;
    private boolean autoOpenEnabled;
    private boolean rollAnimationEnabled = true;
    private PetVisibility petVisibility = PetVisibility.ALL;
    /** Every pet this player has ever obtained - equipped or sitting in storage, this IS the Bag's whole inventory. Each is a real, permanent instance with its own level/XP (see {@link PetInstance}) - never lost on unequip. */
    private List<PetInstance> pets = new ArrayList<>();
    /** Ordered instance ids from {@link #pets} currently equipped - equip-slot order, same role {@code equippedItemIds} used to play before the per-instance migration. */
    private List<UUID> equippedPetIds = new ArrayList<>();
    private SendMode sendMode = SendMode.MANUAL;
    private AttackMode attackMode = AttackMode.SINGLE;
    private Map<String, Set<String>> packCollectionProgress = new HashMap<>();
    private Map<String, Long> lastObtainedAt = new HashMap<>();
    private Map<String, Integer> storedPacks = new HashMap<>();
    private String activePackId;
    private long lastStockCycleId = -1;
    private Map<String, Integer> stockPurchasedThisCycle = new HashMap<>();
    private long rollCount;
    private AutoTargetMode autoTargetMode = AutoTargetMode.CLOSEST;
    private long lastQuestResetDay = -1;
    private Map<String, Integer> questProgress = new HashMap<>();
    private Set<String> claimedQuestIds = new HashSet<>();
    // category id -> chosen difficulty ("EASY"/"MEDIUM"/"HARD") for today -
    // locked in once picked, cleared on the next quest-day reset alongside
    // questProgress/claimedQuestIds - see yield-quests' QuestService.
    private Map<String, String> selectedQuestDifficultyByCategory = new HashMap<>();
    private int prestiges;
    private BigInteger prestigePoints = BigInteger.ZERO;
    private Map<String, Integer> skillTreeLevels = new HashMap<>();
    /** Upgrade type id -> global level, bought at yield-upgrades' physical zone stations - shared across every station of that type, see UpgradeService. */
    private Map<String, Integer> upgradeLevels = new HashMap<>();
    private boolean autoFuseEnabled;
    /** True once this player has been shown (or auto-defaulted through) the first-join starter pet choice - see StarterPetService. */
    private boolean starterPetGranted;
    private long lifetimeCubeKills;
    private BigInteger lifetimeCoinsEarned = BigInteger.ZERO;
    /** Lifetime damage dealt to world bosses (see yield-zones' WorldBossService) - drives the leaderboard system, same as lifetimeCubeKills. */
    private long lifetimeBossDamage;
    private String equippedChatColor;
    private String equippedNameplate;
    private String equippedTag;
    // Zone ids this player has purchased/unlocked - see ZoneUnlockCost/
    // ZoneLockService (yield-zones). A zone with a free ZoneUnlockCost is
    // never checked against this set at all, so it doesn't need an entry
    // here to be accessible.
    private Set<String> unlockedZoneIds = new HashSet<>();
    // The team this player currently belongs to, or null - the actual Team
    // document (name/members/trophy balance/upgrades) lives in its own
    // yield-teams collection, keyed by this id. See yield-teams' TeamStore.
    private UUID teamId;
    // Achievements/Milestones (see yield-achievements) - both keep their
    // progress as a plain id -> counter map, same idiom as questProgress
    // above, rather than reusing existing lifetime-stat fields (cube kills,
    // etc.) directly - keeping every trackable stat generic and config-
    // driven, not tied to whichever fields happen to already exist here.
    private BigInteger credits = BigInteger.ZERO;
    private Map<String, Long> achievementProgress = new HashMap<>();
    private Set<String> claimedAchievementIds = new HashSet<>();
    // categoryId -> counter, shared across every tier within that category.
    private Map<String, Long> milestoneProgress = new HashMap<>();
    // "categoryId:tierIndex" composite keys - a category can have several
    // tiers complete-but-unclaimed at once if progress jumped past more
    // than one threshold, and each needs claiming independently.
    private Set<String> claimedMilestoneKeys = new HashSet<>();
    // Blocktree (see yield-blocktree) - same idiom as milestoneProgress/
    // claimedMilestoneKeys above, just keyed by Bukkit Material name instead
    // of an arbitrary category id.
    private Map<String, Long> blockTreeProgress = new HashMap<>();
    private Set<String> claimedBlockTreeTiers = new HashSet<>();
    // Pickaxe enchants (see yield-mining) - keyed by the enchant's config id
    // (e.g. "greed"). A missing entry means level/mastery 0, same idiom as
    // blockTreeProgress's own "absent = 0" convention. pickaxeEnchantDisabled
    // is a player-toggled "don't roll this one" preference, not a lock -
    // disenchanting instead removes/refunds the level entirely.
    private Map<String, Integer> pickaxeEnchantLevels = new HashMap<>();
    private Map<String, Integer> pickaxeEnchantMastery = new HashMap<>();
    private Set<String> pickaxeEnchantDisabled = new HashSet<>();
    // Player leveling (see yield-leveling) - a whole-player level/XP stat,
    // separate from any individual pet's own level/XP. Synced to the
    // player's real vanilla XP bar (Player#setLevel/setExp) rather than
    // rendered through any custom UI - see PlayerLevelingService.
    private int playerLevel = 1;
    private long playerXp;
    /** A permanent, admin-granted luck bonus (see /admin stats) - additive, same slot shape as every other LuckService contributor. */
    private double adminLuckBonus;
    // Permanent shard bonuses (see me.dontshare.yieldpacks.shard.ShardService)
    // - each right-click-consumed Shard nudges its own stat up forever, never
    // resets, never decays. Additive percentages, same "1.0 + total" shape
    // every other multiplier in this codebase already uses.
    private double shardDamageBonus;
    private double shardCoinBonus;
    private double shardGemBonus;
    private double shardLuckBonus;
    private double shardAttackSpeedBonus;
    private double shardCritChanceBonus;
    // Login streak (see yield-quests' LoginStreakService) - lastLoginEpochDay
    // is LocalDate#toEpochDay() of the last day a login was actually
    // credited (never the same day twice), loginStreak the current
    // consecutive-day count.
    private long lastLoginEpochDay;
    private int loginStreak;
    // Active potion effects (see yield-achievements' PotionService) - keyed
    // by "STAT_MULTIPLIER" (e.g. "COINS_2.0") so two potions of the same
    // stat AND multiplier stack duration together, while a different
    // multiplier of the same stat runs as its own separate, concurrent
    // effect. Value is an absolute expiry epoch millis, not a remaining
    // duration, so it keeps counting down correctly across a relog/restart.
    private Map<String, Long> activePotionExpiryMillis = new HashMap<>();
    /** Purchased rank id ("vip"/"celestial") from yield-ranks' store products, or null - see DonorRankService. Distinct from the unrelated prestige `rank` int above. */
    private String donorRankId;
    // Ore Bag (see yield-mining's OreBagService) - entry id -> "MATERIAL:multiplier:tierId".
    // Generic string encoding, not a yield-mining type: yield-packs can't
    // depend on yield-mining (the dependency points the other way), same
    // reasoning as PetInstance's own forgeBonuses map.
    private Map<String, String> oreBagEntries = new LinkedHashMap<>();
    private boolean oreBagNotificationsEnabled = true;
    // Ore materials (Material#name()) this player has ever pulled a Special
    // Ore of - drives /oreindex's discovered-vs-locked state.
    private Set<String> discoveredOreMaterials = new HashSet<>();
    // Enchant slots (see yield-packs' EnchantService) - index = slot
    // position (0-8, 9 total matching PS99's own 6 free + 3 premium),
    // value "" (empty/unfilled) or "TYPE:RARITY_ID". The durable source
    // of truth; EnchantGui regenerates the real item shown in each slot
    // fresh on every open, same "encode as string, rebuild the display
    // item on demand" idiom as the Ore Bag's own entries.
    private List<String> enchantSlots = newEmptyEnchantSlots();
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

    public BigInteger getGems() {
        return gems;
    }

    public void setGems(BigInteger gems) {
        this.gems = gems;
    }

    public int getRebirths() {
        return rebirths;
    }

    public void setRebirths(int rebirths) {
        this.rebirths = rebirths;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    /** Whether PackOpenService should keep auto-opening {@link #getActivePackId()} until its storage runs out. */
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

    public List<PetInstance> getPets() {
        return pets;
    }

    public void setPets(List<PetInstance> pets) {
        this.pets = pets;
    }

    public List<UUID> getEquippedPetIds() {
        return equippedPetIds;
    }

    public void setEquippedPetIds(List<UUID> equippedPetIds) {
        this.equippedPetIds = equippedPetIds;
    }

    /** Looks up one specific pet by its permanent instance id - empty if it's been deleted/fused away. */
    public Optional<PetInstance> findPet(UUID instanceId) {
        return pets.stream().filter(pet -> pet.getInstanceId().equals(instanceId)).findFirst();
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
        pets.add(pet);
        packCollectionProgress.computeIfAbsent(packId, ignored -> new HashSet<>()).add(itemId);
        lastObtainedAt.put(itemId, System.currentTimeMillis());
        return pet;
    }

    /** Unopened pack inventory - packId -> how many of that pack this player owns but hasn't opened yet. */
    public Map<String, Integer> getStoredPacks() {
        return storedPacks;
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

    /** Epoch day (LocalDate.toEpochDay()) that {@link #getQuestProgress()}/{@link #getClaimedQuestIds()} were last reset for - see yield-quests' QuestService. */
    public long getLastQuestResetDay() {
        return lastQuestResetDay;
    }

    public void setLastQuestResetDay(long lastQuestResetDay) {
        this.lastQuestResetDay = lastQuestResetDay;
    }

    /** Today's progress toward each daily quest's goal, keyed by quest id - cleared on each new day. */
    public Map<String, Integer> getQuestProgress() {
        return questProgress;
    }

    public void setQuestProgress(Map<String, Integer> questProgress) {
        this.questProgress = questProgress;
    }

    /** Which of today's daily quests have already had their reward claimed - cleared on each new day. */
    public Set<String> getClaimedQuestIds() {
        return claimedQuestIds;
    }

    public void setClaimedQuestIds(Set<String> claimedQuestIds) {
        this.claimedQuestIds = claimedQuestIds;
    }

    /** category id -> chosen difficulty ("EASY"/"MEDIUM"/"HARD") for today, locked in once picked - see yield-quests' QuestService. */
    public Map<String, String> getSelectedQuestDifficultyByCategory() {
        return selectedQuestDifficultyByCategory;
    }

    public void setSelectedQuestDifficultyByCategory(Map<String, String> selectedQuestDifficultyByCategory) {
        this.selectedQuestDifficultyByCategory = selectedQuestDifficultyByCategory;
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

    /** Skill tree node id -> level, one flat namespace shared by every tree (node ids are unique tree-wide). */
    public Map<String, Integer> getSkillTreeLevels() {
        return skillTreeLevels;
    }

    public void setSkillTreeLevels(Map<String, Integer> skillTreeLevels) {
        this.skillTreeLevels = skillTreeLevels;
    }

    /** Upgrade type id -> global level - see yield-upgrades' UpgradeService. */
    public Map<String, Integer> getUpgradeLevels() {
        return upgradeLevels;
    }

    public void setUpgradeLevels(Map<String, Integer> upgradeLevels) {
        this.upgradeLevels = upgradeLevels;
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
    public String getEquippedChatColor() {
        return equippedChatColor;
    }

    public void setEquippedChatColor(String equippedChatColor) {
        this.equippedChatColor = equippedChatColor;
    }

    /** Equipped nameplate cosmetic id, or null for none - see yield-cosmetics. */
    public String getEquippedNameplate() {
        return equippedNameplate;
    }

    public void setEquippedNameplate(String equippedNameplate) {
        this.equippedNameplate = equippedNameplate;
    }

    /** Equipped tag cosmetic id, or null for none - see yield-cosmetics. */
    public String getEquippedTag() {
        return equippedTag;
    }

    public void setEquippedTag(String equippedTag) {
        this.equippedTag = equippedTag;
    }

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

    public String getDonorRankId() {
        return donorRankId;
    }

    public void setDonorRankId(String donorRankId) {
        this.donorRankId = donorRankId;
    }

    public Map<String, String> getOreBagEntries() {
        return oreBagEntries;
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

    public List<String> getEnchantSlots() {
        return enchantSlots;
    }

    public Map<String, Long> getMasteryXp() {
        return masteryXp;
    }

    public Map<String, Long> getAchievementProgress() {
        return achievementProgress;
    }

    public Set<String> getClaimedAchievementIds() {
        return claimedAchievementIds;
    }

    public Map<String, Long> getMilestoneProgress() {
        return milestoneProgress;
    }

    public Set<String> getClaimedMilestoneKeys() {
        return claimedMilestoneKeys;
    }

    public Map<String, Long> getBlockTreeProgress() {
        return blockTreeProgress;
    }

    public Set<String> getClaimedBlockTreeTiers() {
        return claimedBlockTreeTiers;
    }

    public Map<String, Integer> getPickaxeEnchantLevels() {
        return pickaxeEnchantLevels;
    }

    public Map<String, Integer> getPickaxeEnchantMastery() {
        return pickaxeEnchantMastery;
    }

    public Set<String> getPickaxeEnchantDisabled() {
        return pickaxeEnchantDisabled;
    }

    public int getPlayerLevel() {
        return playerLevel;
    }

    public void setPlayerLevel(int playerLevel) {
        this.playerLevel = playerLevel;
    }

    public long getPlayerXp() {
        return playerXp;
    }

    public void setPlayerXp(long playerXp) {
        this.playerXp = playerXp;
    }

    public Map<String, Long> getActivePotionExpiryMillis() {
        return activePotionExpiryMillis;
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

    public double getShardGemBonus() {
        return shardGemBonus;
    }

    public void setShardGemBonus(double shardGemBonus) {
        this.shardGemBonus = shardGemBonus;
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

    public long getLastLoginEpochDay() {
        return lastLoginEpochDay;
    }

    public void setLastLoginEpochDay(long lastLoginEpochDay) {
        this.lastLoginEpochDay = lastLoginEpochDay;
    }

    public int getLoginStreak() {
        return loginStreak;
    }

    public void setLoginStreak(int loginStreak) {
        this.loginStreak = loginStreak;
    }
}
