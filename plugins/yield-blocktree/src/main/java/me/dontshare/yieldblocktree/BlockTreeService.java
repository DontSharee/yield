package me.dontshare.yieldblocktree;

import me.dontshare.yieldblocktree.data.BlockTreeDefinition;
import me.dontshare.yieldblocktree.data.BlockTreeEffect;
import me.dontshare.yieldblocktree.data.BlockTreeEffectType;
import me.dontshare.yieldblocktree.data.BlockTreeTier;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Per-material break tracking and tier claiming - same claim-is-an-explicit-
 * action shape as yield-achievements' MilestoneService (tiers unlock
 * sequentially, one claim at a time), plus a set of query helpers that
 * re-derive every effect's live total from {@code claimedBlockTreeTiers}
 * on every call rather than caching a running total, so editing
 * blocktree.yml's values later just works with no migration needed.
 */
public final class BlockTreeService {

    public enum TierState {
        INCOMPLETE,
        IN_PROGRESS,
        COMPLETE_UNCLAIMED,
        CLAIMED
    }

    public enum ClaimResult {
        SUCCESS,
        NOT_COMPLETE,
        ALREADY_CLAIMED,
        LOCKED,
        UNKNOWN
    }

    private final Supplier<Map<Material, BlockTreeDefinition>> content;
    private final PlayerDataStore<PackPlayerProfile> store;

    public BlockTreeService(Supplier<Map<Material, BlockTreeDefinition>> content, PlayerDataStore<PackPlayerProfile> store) {
        this.content = content;
        this.store = store;
    }

    /** One rung of a block's tree whose goal was just reached by a single {@link #recordBreak} call - carries its own index (not just the {@link BlockTreeTier} itself) since a caller wanting to name it ("Tier 3 unlocked!") needs that, not just the goal/effects. */
    public record TierCrossed(int tierIndex, BlockTreeTier tier) {
    }

    /**
     * Called from the OreCubeKilledEvent listener - a no-op for any material
     * this config doesn't track. The actual increment is scaled by {@link
     * #progressMultiplierContribution} (a PROGRESS_MULTIPLIER perk counts
     * every break - of ANY tracked block - as more than one, making the
     * whole system faster to climb, not just the block that granted it).
     * <p>
     * Returns every tier whose goal fell strictly between the progress
     * before and after THIS call - empty for an ordinary break that didn't
     * cross anything, and (rare, but possible with a high progress
     * multiplier jumping several goals in one hit) more than one entry.
     * Reaching a tier's goal is otherwise completely silent - claiming it
     * is a separate, manual GUI action - so this is what a caller with a
     * real {@code Player} (this service has none) hooks a "you just hit
     * tier N" moment into, rather than the crossing going unremarked until
     * the player happens to open the menu later.
     */
    public List<TierCrossed> recordBreak(Player player, Material material) {
        var def = content.get().get(material);
        if (def == null) {
            return List.of();
        }
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return List.of();
        }
        long before = progressOf(profile, material);
        long amount = Math.max(1, Math.round(progressMultiplierContribution(profile)));
        long after = before + amount;
        profile.getBlockTreeProgress().merge(material.name(), amount, Long::sum);
        store.save(player.getUniqueId());

        List<TierCrossed> crossed = new ArrayList<>();
        List<BlockTreeTier> tiers = def.tiers();
        for (int i = 0; i < tiers.size(); i++) {
            BlockTreeTier tier = tiers.get(i);
            if (tier.goal() > before && tier.goal() <= after) {
                crossed.add(new TierCrossed(i, tier));
            }
        }
        return crossed;
    }

    /** The configured display name for this material's tree, or its raw enum name if it isn't tracked at all - used for the tier-crossed announcement (see {@link #recordBreak}). */
    public String displayNameOf(Material material) {
        var def = content.get().get(material);
        return def != null ? def.displayName() : material.name();
    }

    public long progressOf(PackPlayerProfile profile, Material material) {
        return profile.getBlockTreeProgress().getOrDefault(material.name(), 0L);
    }

    public TierState stateOf(PackPlayerProfile profile, Material material, int tierIndex, BlockTreeTier tier) {
        if (profile.getClaimedBlockTreeTiers().contains(key(material, tierIndex))) {
            return TierState.CLAIMED;
        }
        if (!previousClaimed(profile, material, tierIndex)) {
            return TierState.INCOMPLETE;
        }
        long progress = progressOf(profile, material);
        if (progress >= tier.goal()) {
            return TierState.COMPLETE_UNCLAIMED;
        }
        return progress > 0 ? TierState.IN_PROGRESS : TierState.INCOMPLETE;
    }

    private boolean previousClaimed(PackPlayerProfile profile, Material material, int tierIndex) {
        return tierIndex == 0 || profile.getClaimedBlockTreeTiers().contains(key(material, tierIndex - 1));
    }

    public ClaimResult claim(Player player, Material material, int tierIndex) {
        BlockTreeDefinition def = content.get().get(material);
        if (def == null || tierIndex < 0 || tierIndex >= def.tiers().size()) {
            return ClaimResult.UNKNOWN;
        }
        BlockTreeTier tier = def.tiers().get(tierIndex);
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        String key = key(material, tierIndex);
        if (profile.getClaimedBlockTreeTiers().contains(key)) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        if (!previousClaimed(profile, material, tierIndex)) {
            return ClaimResult.LOCKED;
        }
        if (progressOf(profile, material) < tier.goal()) {
            return ClaimResult.NOT_COMPLETE;
        }
        profile.getClaimedBlockTreeTiers().add(key);
        store.save(player.getUniqueId());
        return ClaimResult.SUCCESS;
    }

    /** Admin/support override - see MilestoneService#forceClaim's own Javadoc for why every earlier tier gets marked claimed too. */
    public ClaimResult forceClaim(Player player, Material material, int tierIndex) {
        BlockTreeDefinition def = content.get().get(material);
        if (def == null || tierIndex < 0 || tierIndex >= def.tiers().size()) {
            return ClaimResult.UNKNOWN;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        String key = key(material, tierIndex);
        if (profile.getClaimedBlockTreeTiers().contains(key)) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        for (int i = 0; i < tierIndex; i++) {
            profile.getClaimedBlockTreeTiers().add(key(material, i));
        }
        long goal = def.tiers().get(tierIndex).goal();
        if (progressOf(profile, material) < goal) {
            profile.getBlockTreeProgress().put(material.name(), goal);
        }
        store.save(player.getUniqueId());
        return claim(player, material, tierIndex);
    }

    private String key(Material material, int tierIndex) {
        return material.name() + ":" + tierIndex;
    }

    // ---- Effect query helpers - each re-sums live claimed-tier state, see class Javadoc ----

    public long flatCoinBonus(PackPlayerProfile profile, Material material) {
        return Math.round(sumBlockScoped(profile, material, BlockTreeEffectType.FLAT_COINS));
    }

    public long flatGemBonus(PackPlayerProfile profile, Material material) {
        return Math.round(sumBlockScoped(profile, material, BlockTreeEffectType.FLAT_GEMS));
    }

    public long flatCreditBonus(PackPlayerProfile profile, Material material) {
        return Math.round(sumBlockScoped(profile, material, BlockTreeEffectType.FLAT_CREDITS));
    }

    /** 1.0 + every claimed BLOCK_COIN_MULTIPLIER for this material - a factor, ready to multiply straight into a payout formula. */
    public double blockCoinMultiplier(PackPlayerProfile profile, Material material) {
        return 1.0 + sumBlockScoped(profile, material, BlockTreeEffectType.BLOCK_COIN_MULTIPLIER);
    }

    public double blockGemMultiplier(PackPlayerProfile profile, Material material) {
        return 1.0 + sumBlockScoped(profile, material, BlockTreeEffectType.BLOCK_GEM_MULTIPLIER);
    }

    /** 1.0 + every claimed GLOBAL_COIN_MULTIPLIER across every block - same shape yield-skilltree/yield-rebirth register into {@code YieldPacks#coinMultiplier}. */
    public double globalCoinMultiplierContribution(PackPlayerProfile profile) {
        return 1.0 + sumGlobal(profile, BlockTreeEffectType.GLOBAL_COIN_MULTIPLIER);
    }

    public double globalDamageMultiplierContribution(PackPlayerProfile profile) {
        return 1.0 + sumGlobal(profile, BlockTreeEffectType.GLOBAL_DAMAGE_MULTIPLIER);
    }

    /** Additive, not a factor - matches {@code LuckService#extraLuckProviders}' own convention. */
    public double globalLuckBoostContribution(PackPlayerProfile profile) {
        return sumGlobal(profile, BlockTreeEffectType.GLOBAL_LUCK_BOOST);
    }

    /** 1.0 + every claimed GLOBAL_ATTACK_SPEED_MULTIPLIER - registers straight into {@code YieldPacks#attackSpeedMultiplier}, same slot skill tree potions could occupy. */
    public double globalAttackSpeedMultiplierContribution(PackPlayerProfile profile) {
        return 1.0 + sumGlobal(profile, BlockTreeEffectType.GLOBAL_ATTACK_SPEED_MULTIPLIER);
    }

    /** 1.0 + every claimed ROLL_SPEED_MULTIPLIER - registers straight into {@code PackOpenService#registerCooldownMultiplierProvider} (higher shortens the open cooldown). */
    public double rollSpeedMultiplierContribution(PackPlayerProfile profile) {
        return 1.0 + sumGlobal(profile, BlockTreeEffectType.ROLL_SPEED_MULTIPLIER);
    }

    public double doubleHitChance(PackPlayerProfile profile) {
        return sumGlobal(profile, BlockTreeEffectType.DOUBLE_HIT_CHANCE);
    }

    /** Rolled independently of {@link #doubleHitChance} - see {@code PetCombatController}. */
    public double tripleHitChance(PackPlayerProfile profile) {
        return sumGlobal(profile, BlockTreeEffectType.TRIPLE_HIT_CHANCE);
    }

    public double exclusiveFindChance(PackPlayerProfile profile) {
        return sumGlobal(profile, BlockTreeEffectType.EXCLUSIVE_FIND_CHANCE);
    }

    /** Additive on top of a cube kill's own base gem-drop chance - see {@code OreCubeService#payOut}. */
    public double gemChanceBoost(PackPlayerProfile profile) {
        return sumGlobal(profile, BlockTreeEffectType.GEM_CHANCE_BOOST);
    }

    /** 1.0 + every claimed PROGRESS_MULTIPLIER - see {@link #recordBreak}. */
    public double progressMultiplierContribution(PackPlayerProfile profile) {
        return 1.0 + sumGlobal(profile, BlockTreeEffectType.PROGRESS_MULTIPLIER);
    }

    /** Summed drop chance for this exact material+shard-type combo, across every claimed UNLOCK_SHARD_DROP effect on that block's own tree - 0.0 if the player hasn't claimed any tier unlocking that shard from this block (i.e. it simply can't drop for them yet). */
    public double shardDropChance(PackPlayerProfile profile, Material material, String shardTypeName) {
        var def = content.get().get(material);
        if (def == null) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < def.tiers().size(); i++) {
            if (!profile.getClaimedBlockTreeTiers().contains(key(material, i))) {
                continue;
            }
            for (BlockTreeEffect effect : def.tiers().get(i).effects()) {
                if (effect.type() == BlockTreeEffectType.UNLOCK_SHARD_DROP && shardTypeName.equals(effect.data())) {
                    total += effect.value();
                }
            }
        }
        return total;
    }

    private double sumBlockScoped(PackPlayerProfile profile, Material material, BlockTreeEffectType type) {
        BlockTreeDefinition def = content.get().get(material);
        if (def == null) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < def.tiers().size(); i++) {
            if (!profile.getClaimedBlockTreeTiers().contains(key(material, i))) {
                continue;
            }
            for (BlockTreeEffect effect : def.tiers().get(i).effects()) {
                if (effect.type() == type) {
                    total += effect.value();
                }
            }
        }
        return total;
    }

    private double sumGlobal(PackPlayerProfile profile, BlockTreeEffectType type) {
        double total = 0.0;
        for (Map.Entry<Material, BlockTreeDefinition> entry : content.get().entrySet()) {
            total += sumBlockScoped(profile, entry.getKey(), type);
        }
        return total;
    }
}
