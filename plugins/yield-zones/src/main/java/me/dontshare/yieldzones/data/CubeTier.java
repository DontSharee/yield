package me.dontshare.yieldzones.data;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * One tier of ore cube a zone can roll - how tough it is, what it pays out
 * (coins, diamonds and XP), and how often it shows up.
 * <p>
 * {@code diamondValue} is the SIZE of a diamond drop, not its odds: the
 * roll itself is a flat ~5%-times-luck chance wherever a cube dies (see
 * {@code OreCubeService}'s own payout), and this is how many diamonds that
 * roll actually hands over. Without it a zone-20 cube paid exactly the
 * same single diamond a meadow cube did, which left diamond income flat
 * across the entire game while everything priced in diamonds (ranks, pet
 * enchants) kept climbing - so ranks simply stopped being reachable a few
 * zones in.
 * <p>
 * A TREASURE tier is an ordinary tier in every mechanical respect - it
 * falls, lands, has HP, takes damage and pays out through exactly the same
 * path - with three differences that are all about making it an event:
 * it always glows so it can be spotted from across the zone, it pays far
 * more per point of HP than a normal cube, and it hands over a stack of
 * that zone's own packs on top of the coins ({@code rewardPackId} /
 * {@code rewardPackAmount}). Modelling it as a tier rather than a separate
 * entity type is deliberate: everything that already works for a cube -
 * targeting, combo, block-tree credit, the damage pipeline - works for a
 * chest for free, and there is no second system to keep in sync.
 * <p>
 * A GIANT tier (see {@code giant-cubes:} in zones.yml) is ordinary in the
 * same way, just bigger: {@code size} is its edge length in blocks, and
 * every piece of a cube that assumed 1x1x1 - the falling and landed body,
 * the bonus glow, the look-at outline, the click and highlight hitboxes,
 * the hit squish, the HP label, where pets aim - reads it instead.
 * {@code label} is the name shown above it ("BIG SAFE"), or null for a
 * cube that is only its material.
 */
public record CubeTier(Material material, long maxHp, long coinValue, long diamondValue, long xpValue, double weight,
                        boolean treasure, String rewardPackId, int rewardPackAmount,
                        float size, String label, NamedTextColor glow) {

    /** An ordinary, non-treasure tier - the shape every cube-tiers entry loads as. */
    public CubeTier(Material material, long maxHp, long coinValue, long diamondValue, long xpValue, double weight) {
        this(material, maxHp, coinValue, diamondValue, xpValue, weight, false, null, 0, 1f, null, null);
    }

    /** A treasure tier - normal size, no label of its own. */
    public CubeTier(Material material, long maxHp, long coinValue, long diamondValue, long xpValue, double weight,
                    boolean treasure, String rewardPackId, int rewardPackAmount) {
        this(material, maxHp, coinValue, diamondValue, xpValue, weight, treasure, rewardPackId, rewardPackAmount,
                1f, null, null);
    }

    /** Bigger than a block - everything size-dependent branches on this rather than on which config section a tier came from. */
    public boolean giant() {
        return size > 1f;
    }
}
