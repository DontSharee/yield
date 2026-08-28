package me.dontshare.yieldpacks.display;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Pure formation-offset math: where each equipped pet sits relative to its
 * owner, in a grid that fans out directly behind them and rotates with
 * their facing (yaw). No fixed row/column cap - equipping another pet just
 * extends the grid with another row.
 */
public final class PetFormation {

    private PetFormation() {
    }

    /** {@code index} is the pet's position in equip-slot order (0-based); {@code ownerLocation} should be the owner's feet location. */
    public static Location positionFor(Location ownerLocation, int index, PetDisplayConfig config) {
        int columns = Math.max(1, config.gridColumns());
        int row = index / columns;
        int col = index % columns;
        double centeredCol = col - (columns - 1) / 2.0;

        double yawRad = Math.toRadians(ownerLocation.getYaw());
        // Same convention Bukkit's own Location#getDirection uses for yaw -> horizontal facing vector.
        Vector facing = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());

        double backDistance = config.startDistance() + row * config.rowSpacing();
        Vector offset = facing.multiply(-backDistance).add(right.multiply(centeredCol * config.columnSpacing()));

        Location result = ownerLocation.clone().add(offset);
        result.setY(ownerLocation.getY() + config.heightOffset());
        // The entity's own body yaw/pitch in the spawn/teleport packet don't
        // drive a Display entity's visual orientation (that's controlled
        // entirely by the fixed rotation quaternion PetDisplayService sets
        // once at spawn - see ItemDisplayManager#setRotation) and would
        // otherwise just inherit the owner's live look direction for no
        // reason, so they're zeroed out here rather than left meaningful.
        result.setYaw(0f);
        result.setPitch(0f);
        return result;
    }
}
