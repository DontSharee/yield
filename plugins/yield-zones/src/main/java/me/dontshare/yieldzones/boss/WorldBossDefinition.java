package me.dontshare.yieldzones.boss;

import org.bukkit.Location;
import org.bukkit.Material;

/**
 * One configured world boss (see {@code worldboss.yml}) - {@code size} is
 * both the giant visual {@code BlockDisplay}'s scale AND the real barrier
 * hitbox's cube dimensions (blocks), kept in lockstep on purpose so the
 * clickable volume always matches what's actually drawn. Only one instance
 * of a given boss id can be alive at once - see {@code WorldBossService}.
 */
public record WorldBossDefinition(String id, String zoneId, String displayName, Material material, int size,
                                   Location location, long maxHp, long checkIntervalMillis, double spawnChance,
                                   long rewardCoins, long rewardDiamonds, long despawnAfterMillis) {
}
