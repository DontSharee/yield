package me.dontshare.yieldpacks.shard;

/**
 * A permanent, stacking stat shard - right-click consumed for a small,
 * forever-permanent multiplier bump (see {@link ShardService}). Dropped
 * only from a block whose own tree has unlocked that shard type (see
 * yield-blocktree's {@code UNLOCK_SHARD_DROP} effect) - this enum's own
 * {@code name()} is the exact string a blocktree.yml effect's {@code data}
 * field must match.
 */
public enum ShardType {
    DAMAGE,
    COINS,
    GEMS,
    LUCK,
    ATTACK_SPEED,
    CRIT_CHANCE
}
