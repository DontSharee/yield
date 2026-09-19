package me.dontshare.yieldzones.data;

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
 */
public record CubeTier(Material material, long maxHp, long coinValue, long diamondValue, long xpValue, double weight) {
}
