package me.dontshare.yieldpacks.pity;

/** One pity milestone - every {@code rolls} pack opens, the very next roll gets {@code multiplier} luck. */
public record PityTier(int rolls, double multiplier) {
}
