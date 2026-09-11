package me.dontshare.yieldzones.data;

import org.bukkit.Material;

/**
 * One cuboid segment of a locked zone's fake, client-side-only barricade - a
 * zone can configure more than one (e.g. to wall off several entrances)
 * each with their own appearance, though every shipped example so far uses
 * a single {@code TINTED_GLASS} shell. Purely visual: {@code ZoneLockService}
 * is what actually stops a player from walking through one.
 */
public record ZoneWall(ZoneRegion region, Material material) {
}
