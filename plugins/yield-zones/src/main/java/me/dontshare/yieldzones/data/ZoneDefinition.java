package me.dontshare.yieldzones.data;

import org.bukkit.Location;

import java.util.List;

/**
 * {@code maxConcurrentCubes} caps how many cubes any one player can have live
 * at once in this zone - each player's cubes are their own private, entirely
 * client-side instance (see OreCubeService), so unlike a real shared-world
 * mob cap there's no server-wide total to also cap: one player's cubes cost
 * nothing extra as more players pile into the same zone.
 * <p>
 * {@code unlockCost} being {@link ZoneUnlockCost#isFree()} means this zone is
 * open to everyone with no wall/purchase gate at all - see
 * {@code ZoneLockService}. {@code walls} is empty for such a zone.
 * {@code teleport} is where fast travel and a locked-zone's purchase confirm
 * send the player - see {@code ZoneContentLoader} for its default when
 * unconfigured.
 */
public record ZoneDefinition(String id, String displayName, ZoneRegion region, List<CubeTier> cubeTiers,
                              List<CubeBonus> cubeBonuses, ZoneUnlockCost unlockCost, List<ZoneWall> walls,
                              Location teleport, long respawnDelayMillis, int maxConcurrentCubes) {
}
