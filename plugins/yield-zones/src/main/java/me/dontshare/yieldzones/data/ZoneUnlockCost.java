package me.dontshare.yieldzones.data;

import org.bukkit.Material;

import java.math.BigInteger;
import java.util.List;

/**
 * A zone's unlock price - any combination of Coins, Diamonds, and turned-in
 * items. All-zero/empty (the default when a zone's config has no
 * {@code unlock:} section at all) means the zone is free and already
 * unlocked for everyone - no wall gets rendered for it and no purchase gate
 * ever applies, exactly like the shipped "meadow" zone today.
 */
public record ZoneUnlockCost(BigInteger coins, BigInteger diamonds, List<ItemCost> items) {

    public static final ZoneUnlockCost FREE = new ZoneUnlockCost(BigInteger.ZERO, BigInteger.ZERO, List.of());

    public boolean isFree() {
        return coins.signum() <= 0 && diamonds.signum() <= 0 && items.isEmpty();
    }

    public record ItemCost(Material material, int amount) {
    }
}
