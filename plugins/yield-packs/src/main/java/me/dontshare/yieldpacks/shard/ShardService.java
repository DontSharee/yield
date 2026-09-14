package me.dontshare.yieldpacks.shard;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

/**
 * Consuming a Shard permanently nudges one stat up, forever - no cap, no
 * decay, unlike every other multiplier in this codebase which is either a
 * toggle or scales with a re-buyable level. This is deliberately tiny per
 * shard (so a single find never swings balance) but genuinely permanent,
 * the same "every single one you ever find still counts" idle-game shape
 * Rank's own diamond-multiplier already has - just funded by drops instead of
 * currency.
 */
public final class ShardService {

    private static final double COMMON_BONUS = 0.01; // +1% per common shard
    private static final double PERFECT_BONUS = 0.10; // +10% per perfect shard

    private final PlayerDataStore<PackPlayerProfile> store;

    public ShardService(PlayerDataStore<PackPlayerProfile> store) {
        this.store = store;
    }

    public double damageMultiplier(PackPlayerProfile profile) {
        return 1.0 + profile.getShardDamageBonus();
    }

    public double coinMultiplier(PackPlayerProfile profile) {
        return 1.0 + profile.getShardCoinBonus();
    }

    public double diamondMultiplier(PackPlayerProfile profile) {
        return 1.0 + profile.getShardDiamondBonus();
    }

    /** Additive, not a factor - matches LuckService#extraLuckProviders' own convention (fed straight into it, not the "1.0 +" multiplier shape the other three stats use). */
    public double luckBonus(PackPlayerProfile profile) {
        return profile.getShardLuckBonus();
    }

    public double attackSpeedMultiplier(PackPlayerProfile profile) {
        return 1.0 + profile.getShardAttackSpeedBonus();
    }

    /** Additive, same shape as {@link #luckBonus} - fed into PetCombatController's own critChanceProviders registry (see yield-zones' YieldZones#onEnable), on top of that system's own 10% base rate. */
    public double critChanceBonus(PackPlayerProfile profile) {
        return profile.getShardCritChanceBonus();
    }

    /** Applies one shard's permanent bonus and saves - the ONLY mutation point for shard bonuses, so every consume (regardless of caller) goes through the same amount logic. Returns the resulting TOTAL multiplier for that stat (e.g. 1.07 after this was the 7th damage shard consumed), so a caller can announce "your bonus is now X" without a second profile lookup. */
    public double consume(Player player, ShardType type, boolean perfect) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        double amount = perfect ? PERFECT_BONUS : COMMON_BONUS;
        double result = switch (type) {
            case DAMAGE -> {
                profile.setShardDamageBonus(profile.getShardDamageBonus() + amount);
                yield damageMultiplier(profile);
            }
            case COINS -> {
                profile.setShardCoinBonus(profile.getShardCoinBonus() + amount);
                yield coinMultiplier(profile);
            }
            case DIAMONDS -> {
                profile.setShardDiamondBonus(profile.getShardDiamondBonus() + amount);
                yield diamondMultiplier(profile);
            }
            case LUCK -> {
                profile.setShardLuckBonus(profile.getShardLuckBonus() + amount);
                yield 1.0 + luckBonus(profile);
            }
            case ATTACK_SPEED -> {
                profile.setShardAttackSpeedBonus(profile.getShardAttackSpeedBonus() + amount);
                yield attackSpeedMultiplier(profile);
            }
            case CRIT_CHANCE -> {
                profile.setShardCritChanceBonus(profile.getShardCritChanceBonus() + amount);
                yield 1.0 + critChanceBonus(profile);
            }
        };
        store.save(player.getUniqueId());
        return result;
    }
}
