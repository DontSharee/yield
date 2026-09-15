package me.dontshare.yieldmining.enchant;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.math.FormulaEvaluator;
import me.dontshare.yieldmining.data.MiningProfile;
import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Buying, leveling, mastering, and rolling pickaxe enchants - a direct port
 * of the reference Skript's own pickaxe-upgrade functions
 * (getPickUpgradeChance/getPickUpgradeBoost/getPickUpgradePrice/etc.),
 * simplified to this codebase's actual mining reward channels (coins, ore
 * drop amount, xp - no external "cubeStats"/"skilltree luck"/"perk" bonus
 * layers, since those belong to a different, unrelated server).
 * <p>
 * A "boost" is a direct multiplier applied to the reward channel it procs
 * on (e.g. a level-5000 Greed's {@code 1.0001 * level} formula evaluates to
 * ~1.5, i.e. coins *= 1.5 for that mine) - not an additive percentage.
 * <p>
 * Every enchant's current chance/boost is cached per player (see
 * {@link #recompute}), same as the reference's own
 * {@code cache_pickEnchantChances} - {@link #rollMultiplier} runs on every
 * single mined block, so re-evaluating formulas + mastery math there
 * instead of just reading two cached doubles would be wasted work. The
 * cache is invalidated (recomputed) at the only three points a level or
 * mastery can actually change - {@link #purchase}, {@link #disenchant},
 * {@link #masterUp} - plus on join/reload, where {@link YieldMining}/
 * {@link me.dontshare.yieldmining.MiningService} call {@link #recompute}
 * directly.
 */
public final class PickaxeEnchantService {

    /** Matches the reference's own `loop min(amount, 10000) times` safety cap on a bulk buy's cost summation. */
    private static final int PRICE_SUM_CAP = 10_000;
    private static final int MAX_MASTERY_LEVEL = 5;
    private static final double MASTERY_BOOST_MULTIPLIER_PER_LEVEL = 1.6;
    private static final double MASTERY_CHANCE_BONUS_PER_LEVEL = 0.10;
    private static final double MASTERY_COST_EXPONENT = 5.0;

    private record CachedRoll(double chance, double boost) {
    }

    private final Supplier<Map<String, PickaxeEnchantDefinition>> content;
    private final PlayerDataStore<PackPlayerProfile> store;
    /** This plugin's own enchant state. The pack store above stays for the currency a purchase spends. */
    private final PlayerDataStore<MiningProfile> miningStore;

    /** Player -> enchant id -> its current (chance%, boost) pair - see class doc. */
    private final Map<UUID, Map<String, CachedRoll>> cache = new ConcurrentHashMap<>();

    public PickaxeEnchantService(Supplier<Map<String, PickaxeEnchantDefinition>> content, PlayerDataStore<PackPlayerProfile> store,
                                  PlayerDataStore<MiningProfile> miningStore) {
        this.content = content;
        this.store = store;
        this.miningStore = miningStore;
    }

    public Map<String, PickaxeEnchantDefinition> enchants() {
        return content.get();
    }

    public int levelOf(PackPlayerProfile profile, String enchantId) {
        return miningStore.getOrCreate(profile.getPlayerId()).getEnchantLevels().getOrDefault(enchantId, 0);
    }

    public int masteryOf(PackPlayerProfile profile, String enchantId) {
        return miningStore.getOrCreate(profile.getPlayerId()).getEnchantMastery().getOrDefault(enchantId, 0);
    }

    public boolean isEnabled(PackPlayerProfile profile, String enchantId) {
        return !miningStore.getOrCreate(profile.getPlayerId()).getEnchantDisabled().contains(enchantId);
    }

    /** A player-toggled "don't roll this one" preference - not a lock, just opts it out of {@link #rollMultiplier}. Doesn't change the math, so no recompute needed. */
    public void togglePreference(PackPlayerProfile profile, String enchantId) {
        Set<String> disabled = miningStore.getOrCreate(profile.getPlayerId()).getEnchantDisabled();
        if (!disabled.remove(enchantId)) {
            disabled.add(enchantId);
        }
    }

    /** Sums cost-formula(level) for {@code amount} levels starting at {@code fromLevel}. */
    public BigInteger priceForLevels(PickaxeEnchantDefinition def, int fromLevel, int amount) {
        double total = 0;
        int cappedAmount = Math.min(amount, PRICE_SUM_CAP);
        int level = fromLevel;
        for (int i = 0; i < cappedAmount; i++) {
            total += FormulaEvaluator.evaluate(def.costFormula(), level);
            level++;
        }
        return BigInteger.valueOf(Math.round(total));
    }

    public enum PurchaseResult { SUCCESS, MAXED, CANT_AFFORD, REBIRTH_LOCKED }

    public PurchaseResult purchase(PackPlayerProfile profile, PickaxeEnchantDefinition def, int amount) {
        if (profile.getRebirths() < def.rebirthRequirement()) {
            return PurchaseResult.REBIRTH_LOCKED;
        }
        int currentLevel = levelOf(profile, def.id());
        if (currentLevel >= def.maxLevel()) {
            return PurchaseResult.MAXED;
        }
        int actualAmount = Math.min(amount, def.maxLevel() - currentLevel);
        BigInteger price = priceForLevels(def, currentLevel, actualAmount);
        BigInteger balance = def.costCurrency().balanceOf(profile);
        if (balance.compareTo(price) < 0) {
            return PurchaseResult.CANT_AFFORD;
        }
        def.costCurrency().setBalance(profile, balance.subtract(price));
        miningStore.getOrCreate(profile.getPlayerId()).getEnchantLevels().put(def.id(), currentLevel + actualAmount);
        recompute(profile);
        return PurchaseResult.SUCCESS;
    }

    public enum DisenchantResult { SUCCESS, NO_LEVELS }

    public DisenchantResult disenchant(PackPlayerProfile profile, PickaxeEnchantDefinition def) {
        int currentLevel = levelOf(profile, def.id());
        if (currentLevel <= 0) {
            return DisenchantResult.NO_LEVELS;
        }
        BigInteger refund = priceForLevels(def, 0, currentLevel);
        def.costCurrency().setBalance(profile, def.costCurrency().balanceOf(profile).add(refund));
        miningStore.getOrCreate(profile.getPlayerId()).getEnchantLevels().remove(def.id());
        miningStore.getOrCreate(profile.getPlayerId()).getEnchantMastery().remove(def.id());
        recompute(profile);
        return DisenchantResult.SUCCESS;
    }

    public enum MasteryResult { SUCCESS, NOT_MAXED, ALREADY_MASTERED, CANT_AFFORD }

    /** Base price is a fresh 0->maxLevel buy - the reference's own "flat mastery_price_N override" table is skipped as a rarely-used edge case. */
    public BigInteger masteryCost(PickaxeEnchantDefinition def, int currentMasteryLevel) {
        BigInteger basePrice = priceForLevels(def, 0, def.maxLevel());
        double factor = 1 + Math.pow(MASTERY_COST_EXPONENT, currentMasteryLevel);
        return BigInteger.valueOf(Math.round(basePrice.doubleValue() * factor));
    }

    public MasteryResult masterUp(PackPlayerProfile profile, PickaxeEnchantDefinition def) {
        if (levelOf(profile, def.id()) < def.maxLevel()) {
            return MasteryResult.NOT_MAXED;
        }
        int currentMastery = masteryOf(profile, def.id());
        if (currentMastery >= MAX_MASTERY_LEVEL) {
            return MasteryResult.ALREADY_MASTERED;
        }
        BigInteger cost = masteryCost(def, currentMastery);
        // Mastery is always paid in diamonds (matching the reference), regardless of this enchant's own leveling currency.
        BigInteger diamonds = profile.getDiamonds();
        if (diamonds.compareTo(cost) < 0) {
            return MasteryResult.CANT_AFFORD;
        }
        profile.setDiamonds(diamonds.subtract(cost));
        miningStore.getOrCreate(profile.getPlayerId()).getEnchantMastery().put(def.id(), currentMastery + 1);
        recompute(profile);
        return MasteryResult.SUCCESS;
    }

    /** The percent chance (0-100) this enchant currently procs - cached, see class doc. */
    public double chanceOf(PackPlayerProfile profile, PickaxeEnchantDefinition def) {
        return cachedRoll(profile, def).chance();
    }

    /** The current boost MAGNITUDE - a direct multiplier applied to the reward channel it procs on - cached, see class doc. */
    public double boostOf(PackPlayerProfile profile, PickaxeEnchantDefinition def) {
        return cachedRoll(profile, def).boost();
    }

    private CachedRoll cachedRoll(PackPlayerProfile profile, PickaxeEnchantDefinition def) {
        return cache.computeIfAbsent(profile.getPlayerId(), id -> new ConcurrentHashMap<>())
                .computeIfAbsent(def.id(), id -> computeRoll(profile, def));
    }

    /** Recomputes and re-caches every enchant's chance/boost for this profile - call after any level/mastery change, on join (after the profile's actually cached), and after a content reload. */
    public void recompute(PackPlayerProfile profile) {
        Map<String, CachedRoll> perEnchant = new ConcurrentHashMap<>();
        for (PickaxeEnchantDefinition def : content.get().values()) {
            perEnchant.put(def.id(), computeRoll(profile, def));
        }
        cache.put(profile.getPlayerId(), perEnchant);
    }

    public void clearCache(UUID playerId) {
        cache.remove(playerId);
    }

    private CachedRoll computeRoll(PackPlayerProfile profile, PickaxeEnchantDefinition def) {
        int level = levelOf(profile, def.id());
        int masteryLevel = masteryOf(profile, def.id());

        double chance = FormulaEvaluator.evaluate(def.chanceFormula(), level);
        if (masteryLevel > 0) {
            chance += chance * (MASTERY_CHANCE_BONUS_PER_LEVEL * masteryLevel);
        }

        double boost = FormulaEvaluator.evaluate(def.boostFormula(), level);
        if (masteryLevel > 0) {
            boost *= Math.pow(MASTERY_BOOST_MULTIPLIER_PER_LEVEL, masteryLevel);
        }

        return new CachedRoll(chance, boost);
    }

    /** Rolls every equipped, enabled enchant of this reward type independently - returns the combined multiplier to apply (1.0 = no change if nothing procs). */
    public double rollMultiplier(PackPlayerProfile profile, MiningRewardType type) {
        double multiplier = 1.0;
        for (PickaxeEnchantDefinition def : content.get().values()) {
            if (def.type() != type || levelOf(profile, def.id()) <= 0 || !isEnabled(profile, def.id())) {
                continue;
            }
            double chance = chanceOf(profile, def);
            if (chance > 0 && ThreadLocalRandom.current().nextDouble(100) < chance) {
                multiplier *= boostOf(profile, def);
            }
        }
        return multiplier;
    }
}
