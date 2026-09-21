package me.dontshare.yieldpacks.display;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure formation-offset math: where each equipped pet sits relative to its
 * owner, in rows that fan out directly behind them and rotate with their
 * facing (yaw). No hard cap - equipping another pet just adds another row.
 * <p>
 * Two rules shape it, and both exist because Huges are two and a half times
 * the size of a normal pet:
 * <ul>
 *   <li><b>Huges come first</b>, so they stand in the front rows where they
 *       are the thing you see. A player who owns one is showing it off; it
 *       should not end up behind three Stray Cats.</li>
 *   <li><b>A row holds at most two Huges</b>, and a row carrying a single
 *       Huge still holds three pets - just spread wider. A row that gave
 *       every Huge a full three-wide berth would leave the formation full
 *       of holes.</li>
 * </ul>
 * Every row is centred on the owner's own back rather than on a full row's
 * width, so one equipped pet walks directly behind them instead of off to
 * one side - which is what the old "centre as if the row were full" maths
 * did, and it looked like a bug because it was one.
 */
public final class PetFormation {

    private PetFormation() {
    }

    /** One row of the formation: which slots are in it, and whether it needs Huge-sized spacing. */
    private record Row(List<Integer> slots, boolean hasHuge) {
    }

    /**
     * Every slot's position at once - computes the owner's facing/right unit
     * vectors from their yaw exactly ONCE and reuses them for every slot,
     * rather than redoing that trig per pet. This runs every {@code
     * update-interval-ticks} for every online player with pets equipped, so
     * with a raised equip cap (donor-rank bonus slots) re-deriving identical
     * sin/cos values per pet was pure waste scaling directly with pet count.
     *
     * @param huge one flag per slot, in slot order - true where that slot
     *             holds a Huge. Pass an empty or shorter list and those
     *             slots are simply treated as normal pets.
     */
    public static List<Location> positionsFor(Location ownerLocation, int count, List<Boolean> huge,
                                               PetDisplayConfig config) {
        double yawRad = Math.toRadians(ownerLocation.getYaw());
        // Same convention Bukkit's own Location#getDirection uses for yaw -> horizontal facing vector.
        Vector facing = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());

        List<Row> rows = packRows(count, huge, Math.max(1, config.gridColumns()));
        // Wide enough that a Huge and its neighbour don't share the same
        // airspace, derived from how much bigger a Huge actually renders
        // rather than from a second number that could drift away from it.
        double hugeSpread = (1.0 + config.hugeScaleMultiplier()) / 2.0;

        Location[] results = new Location[count];
        double backDistance = config.startDistance();
        for (Row row : rows) {
            double spacing = config.columnSpacing() * (row.hasHuge() ? hugeSpread : 1.0);
            int inRow = row.slots().size();
            for (int col = 0; col < inRow; col++) {
                // Centred on however many are ACTUALLY in this row, so a
                // lone pet sits dead centre behind the player.
                double centeredCol = col - (inRow - 1) / 2.0;
                Vector offset = facing.clone().multiply(-backDistance)
                        .add(right.clone().multiply(centeredCol * spacing));
                results[row.slots().get(col)] = finish(ownerLocation, offset, row.hasHuge()
                        && isHuge(huge, row.slots().get(col)), config);
            }
            backDistance += config.rowSpacing() * (row.hasHuge() ? hugeSpread : 1.0);
        }
        return new ArrayList<>(List.of(results));
    }

    /**
     * Huges first, then rows filled greedily: two Huges fill a row on their
     * own, one Huge leaves room for two normal pets beside it, and a row of
     * normal pets holds {@code columns} of them.
     */
    private static List<Row> packRows(int count, List<Boolean> huge, int columns) {
        List<Integer> order = new ArrayList<>(count);
        for (int slot = 0; slot < count; slot++) {
            if (isHuge(huge, slot)) {
                order.add(slot);
            }
        }
        for (int slot = 0; slot < count; slot++) {
            if (!isHuge(huge, slot)) {
                order.add(slot);
            }
        }

        List<Row> rows = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int hugesInRow = 0;
        for (int slot : order) {
            boolean slotIsHuge = isHuge(huge, slot);
            int hugesIfAdded = hugesInRow + (slotIsHuge ? 1 : 0);
            // Two Huges are a full row by themselves; anything else fits the
            // normal width.
            int capacity = hugesIfAdded >= 2 ? 2 : columns;
            if (!current.isEmpty() && (hugesIfAdded > 2 || current.size() + 1 > capacity)) {
                rows.add(new Row(List.copyOf(current), hugesInRow > 0));
                current.clear();
                hugesInRow = 0;
                hugesIfAdded = slotIsHuge ? 1 : 0;
            }
            current.add(slot);
            hugesInRow = hugesIfAdded;
        }
        if (!current.isEmpty()) {
            rows.add(new Row(List.copyOf(current), hugesInRow > 0));
        }
        return rows;
    }

    private static boolean isHuge(List<Boolean> huge, int slot) {
        return huge != null && slot < huge.size() && Boolean.TRUE.equals(huge.get(slot));
    }

    /**
     * A Huge sits higher than a normal pet by exactly the amount it is
     * bigger.
     * <p>
     * An item display is centred on its position, so a model rendered at
     * {@code hugeScaleMultiplier} times the normal size hangs that much
     * further below the same anchor - at the shipped 0.85 scale and 2.5x
     * multiplier, a Huge's bottom half was a third of a block underground
     * before the hover bob even took it lower. Lifting by half the
     * difference in height puts a Huge's feet exactly where a normal pet's
     * are, so the whole formation stands on the same floor whatever is in
     * it.
     */
    private static double heightFor(boolean huge, PetDisplayConfig config) {
        if (!huge) {
            return config.heightOffset();
        }
        return config.heightOffset() + config.scale() * (config.hugeScaleMultiplier() - 1f) / 2.0;
    }

    private static Location finish(Location ownerLocation, Vector offset, boolean huge, PetDisplayConfig config) {
        Location result = ownerLocation.clone().add(offset);
        result.setY(ownerLocation.getY() + heightFor(huge, config));
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
