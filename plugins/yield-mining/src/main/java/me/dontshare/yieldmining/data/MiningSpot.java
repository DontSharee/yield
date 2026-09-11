package me.dontshare.yieldmining.data;

import org.bukkit.Material;
import org.bukkit.World;

/**
 * A single admin-placed, purely client-sided mineable position - never a
 * real world block. Created by placing a tagged {@code MiningItem} (see
 * {@code MiningService#onPlace}), visible/mineable by every player via
 * packets alone.
 */
public record MiningSpot(World world, int x, int y, int z, Material material) {

    public String key() {
        return world.getName() + ":" + x + ":" + y + ":" + z;
    }
}
