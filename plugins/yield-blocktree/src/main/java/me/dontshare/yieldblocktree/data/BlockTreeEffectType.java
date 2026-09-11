package me.dontshare.yieldblocktree.data;

import java.util.Locale;

/**
 * What one {@link BlockTreeEffect} actually does once its tier is claimed -
 * see blocktree.yml's own comment header for the full semantics of each.
 * FLAT_* and BLOCK_*_MULTIPLIER are scoped to the specific block whose tree
 * granted them; everything else is global, applying regardless of which
 * block (or pack) triggered it.
 */
public enum BlockTreeEffectType {
    FLAT_COINS,
    FLAT_GEMS,
    FLAT_CREDITS,
    BLOCK_COIN_MULTIPLIER,
    BLOCK_GEM_MULTIPLIER,
    GLOBAL_COIN_MULTIPLIER,
    GLOBAL_DAMAGE_MULTIPLIER,
    GLOBAL_LUCK_BOOST,
    GLOBAL_ATTACK_SPEED_MULTIPLIER,
    ROLL_SPEED_MULTIPLIER,
    DOUBLE_HIT_CHANCE,
    TRIPLE_HIT_CHANCE,
    EXCLUSIVE_FIND_CHANCE,
    GEM_CHANCE_BOOST,
    PROGRESS_MULTIPLIER,
    /** Block-scoped - permanently unlocks a chance for THIS block's own cube kills to drop a Shard of the type named in {@link BlockTreeEffect#data} ("DAMAGE"/"COINS"/"GEMS" - see yield-packs' ShardType), "value" being that base drop chance. See BlockTreeService#shardDropChance. */
    UNLOCK_SHARD_DROP;

    /** Parses blocktree.yml's kebab-case "type" strings (e.g. "flat-coins") into this enum. */
    public static BlockTreeEffectType parse(String raw) {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
