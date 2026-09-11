package me.dontshare.yieldzones.data;

import org.bukkit.Location;
import org.bukkit.World;

/** A simple cuboid region - two opposite corners, normalized to min/max on load. No WorldGuard/schematic dependency needed for this. */
public record ZoneRegion(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static ZoneRegion of(World world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new ZoneRegion(world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(Location location) {
        return location.getWorld().equals(world)
                && location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }
}
