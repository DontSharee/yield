package me.dontshare.yieldblocktree.data;

/**
 * One typed, permanent reward a tier grants once claimed - see {@link
 * BlockTreeEffectType} for what each type means. {@code data} is unused by
 * every numeric-only type (null there); {@code UNLOCK_SHARD_DROP} is the one
 * exception - it needs to name WHICH shard type it unlocks on top of its own
 * numeric drop-chance {@code value}, so this is the one type-specific escape
 * hatch rather than adding a whole second effect shape for one type.
 */
public record BlockTreeEffect(BlockTreeEffectType type, double value, String data) {
    public BlockTreeEffect(BlockTreeEffectType type, double value) {
        this(type, value, null);
    }
}
