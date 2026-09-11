package me.dontshare.yieldpacks.display;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure formation-offset math: where each equipped pet sits relative to its
 * owner, in a grid that fans out directly behind them and rotates with
 * their facing (yaw). No fixed row/column cap - equipping another pet just
 * extends the grid with another row.
 */
public final class PetFormation {

    private PetFormation() {
    }

    /**
     * Every slot's position at once, for the common case (no attack
     * overrides at all) - computes the owner's facing/right unit vectors
     * from their yaw exactly ONCE and reuses them for every slot, instead
     * of {@link #positionFor} being called in a loop and redoing that same
     * trig once per pet. This is called every {@code update-interval-ticks}
     * for every online player with pets equipped, so with an equip cap well
     * past a handful of pets (donor-rank bonus slots), re-deriving
     * identical sin/cos values per pet was pure waste scaling directly with
     * pet count - exactly the kind of cost a heavily-equipped player's own
     * client noticeably pays for on every server tick that sends updates.
     */
    public static List<Location> positionsFor(Location ownerLocation, int count, PetDisplayConfig config) {
        int columns = Math.max(1, config.gridColumns());
        double yawRad = Math.toRadians(ownerLocation.getYaw());
        // Same convention Bukkit's own Location#getDirection uses for yaw -> horizontal facing vector.
        Vector facing = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());

        List<Location> results = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            results.add(offsetPosition(ownerLocation, index, columns, facing, right, config));
        }
        return results;
    }

    /** Single-slot version - only meant for the rarer case of a slot resolved one at a time (see {@code PetDisplayService#resolvePositions}'s mixed formation/attack-ring fallback); recomputes its own facing/right each call, so prefer {@link #positionsFor} whenever resolving more than one slot. */
    public static Location positionFor(Location ownerLocation, int index, PetDisplayConfig config) {
        int columns = Math.max(1, config.gridColumns());
        double yawRad = Math.toRadians(ownerLocation.getYaw());
        Vector facing = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        return offsetPosition(ownerLocation, index, columns, facing, right, config);
    }

    private static Location offsetPosition(Location ownerLocation, int index, int columns, Vector facing, Vector right, PetDisplayConfig config) {
        int row = index / columns;
        int col = index % columns;
        double centeredCol = col - (columns - 1) / 2.0;

        double backDistance = config.startDistance() + row * config.rowSpacing();
        Vector offset = facing.clone().multiply(-backDistance).add(right.clone().multiply(centeredCol * config.columnSpacing()));

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
