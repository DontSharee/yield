package me.dontshare.yieldcore.fakeblock;

import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A falling block that only one player ever sees - and, unlike this class's
 * earlier version, never exists server-side at all. It renders as a purely
 * client-side {@code block_display} entity (see {@link BlockDisplayManager})
 * gliding down to the real terrain at its column; landing is just a
 * scheduled callback, not a real physics event.
 * <p>
 * On landing, the display entity is kept alive (not destroyed) as the
 * cube's permanent visual - see {@link #spawn} - so its scale can be
 * animated later (a hit-reaction squish, say) without any block-vs-entity
 * transition to flicker through. A fake, per-viewer {@link Material#BARRIER}
 * is placed at the same spot via {@link Player#sendBlockChange} purely so
 * vanilla's own client-side block targeting still applies: the familiar
 * hover outline, and solid collision for the owner alone. It's invisible,
 * so it never visually competes with the real-looking display sitting in
 * the same space, and being a fake (owner-only) block rather than a real
 * one, it never affects any other player's movement or view.
 * <p>
 * The previous version of this class spawned a genuine vanilla {@link
 * org.bukkit.entity.FallingBlock}, hidden from everyone but its owner via
 * {@link Player#hideEntity}, and relied on cancelling the {@code
 * EntityChangeBlockEvent} it fires on landing to keep the real world
 * untouched. That kept letting real, everyone-visible blocks slip through in
 * production despite repeated hardening of the cancellation. Going fully
 * client-side removes the entire bug class at its root: there is no real
 * entity to race against and no real block placement to ever fail to
 * cancel, because nothing server-side happens at all.
 */
public final class FakeFallingBlock {

    /** Roughly matches vanilla gravity's overall fall duration for a short drop - purely cosmetic pacing, not a physics simulation. */
    private static final double TICKS_PER_BLOCK = 2.0;

    /** What lets a landed cube still be clicked/highlighted/collided-with the normal vanilla way - see the class Javadoc. */
    private static final BlockData BARRIER = Material.BARRIER.createBlockData();

    private record Fall(Player owner, int entityId, Location landedAt, BukkitTask task) {
    }

    public interface LandedCallback {
        /**
         * {@code blockEntityId}/{@code blockEntityUuid} identify the display
         * entity now permanently sitting at {@code landedAt} - the caller owns
         * it from here on (animate it, glow it via a scoreboard team keyed on
         * its UUID, and destroy it via {@link PacketEntityManager#destroyEntity}
         * once the cube is gone).
         */
        void onLanded(Player owner, Location landedAt, int blockEntityId, UUID blockEntityUuid);
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Fall> falls = new ConcurrentHashMap<>();

    public FakeFallingBlock(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Glides a client-side-only block display down to {@code landedY} at
     * {@code spawnAt}'s column, visible only to {@code owner}. Once it
     * "lands", a fake barrier appears there for click/outline/collision (see
     * class Javadoc) and {@code onLanded} runs, handing the still-alive
     * display entity off to the caller - the real world at that column is
     * never touched, at any point. Returns the fall's id - hand it to
     * {@link #cancel} to stop this specific fall early (e.g. its owner left
     * the zone it belongs to) without waiting out the remaining ticks.
     * <p>
     * {@code landedY} is the caller's call, not derived from real terrain
     * here - an earlier version used {@code getHighestBlockYAt} to find
     * "the ground" itself, which silently broke the instant a caller's
     * landing column had anything real and solid above the intended landing
     * spot (a tree branch, a building overhang): the topmost solid block
     * there could be far above where the caller actually wanted this to
     * land, so the cube would drop an absurd distance and land floating in
     * mid-air on top of whatever it hit first, not on the caller's own
     * floor.
     */
    public UUID spawn(Player owner, Location spawnAt, double landedY, BlockData fallingAppearance,
                       BlockData landedAppearance, LandedCallback onLanded) {
        return spawn(owner, spawnAt, landedY, fallingAppearance, landedAppearance, 1f, onLanded);
    }

    /**
     * {@link #spawn(Player, Location, double, BlockData, BlockData, LandedCallback)}
     * for a block {@code size} blocks big - it falls and lands at that size,
     * centred on its column (see {@link BlockDisplayManager#setBlockSize}).
     * The fake barrier underneath stays a single block: it is only there
     * for vanilla's hover outline and the owner's collision, and a 1x1
     * barrier inside a 1.5-block model is hidden by the model around it.
     */
    public UUID spawn(Player owner, Location spawnAt, double landedY, BlockData fallingAppearance,
                       BlockData landedAppearance, float size, LandedCallback onLanded) {
        Location landedAt = new Location(spawnAt.getWorld(), spawnAt.getBlockX(), landedY, spawnAt.getBlockZ());

        // Hide whatever real block already sits at the landing spot the
        // instant the fall starts, not only once it actually lands there -
        // left alone for the whole ~1-second drop, that real (untouched)
        // block stays fully visible, and if it happens to look anything like
        // the falling cube (most terrain does, at a glance), the two read as
        // one oversized, two-layered shape the moment the display arrives on
        // top of it.
        owner.sendBlockChange(landedAt, BARRIER);

        int entityId = PacketEntityManager.nextEntityId();
        UUID entityUuid = UUID.randomUUID();
        BlockDisplayManager.spawn(owner, entityId, entityUuid, spawnAt);
        BlockDisplayManager.setBlockState(owner, entityId, fallingAppearance.getMaterial());
        // A block_display's transform (translation/scale) isn't a proper
        // full-size identity by default - see spawnHighlight/spawnGlow in
        // OreCubeService, which always set this explicitly even for a
        // near-1:1 overlay. Skipping it here rendered the falling cube as a
        // flattened slab instead of a normal block.
        BlockDisplayManager.setBlockSize(owner, entityId, size);

        double distance = Math.max(0, spawnAt.getY() - landedAt.getY());
        int fallTicks = Math.max(2, (int) Math.round(distance * TICKS_PER_BLOCK));
        BlockDisplayManager.setInterpolation(owner, entityId, 0, fallTicks, fallTicks);
        PacketEntityManager.teleportEntity(owner, entityId, landedAt);

        UUID fallId = UUID.randomUUID();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            falls.remove(fallId);
            BlockDisplayManager.setBlockState(owner, entityId, landedAppearance.getMaterial());
            // Re-affirmed here, not just at spawn - guarantees the display is
            // pixel-exact at its intended size/position (a normal cube exactly
            // matching the fake barrier's own hitbox) at the moment it becomes the
            // permanent, clickable cube, regardless of anything that could
            // have nudged its transform mid-fall.
            BlockDisplayManager.setBlockSize(owner, entityId, size);
            // Bundled so the client applies both in the same frame - sent
            // separately, a one-frame gap between the two showed up as a
            // visible flicker.
            PacketEntityManager.beginBundle(owner);
            owner.sendBlockChange(landedAt, BARRIER);
            PacketEntityManager.endBundle(owner);
            onLanded.onLanded(owner, landedAt, entityId, entityUuid);
        }, fallTicks);
        falls.put(fallId, new Fall(owner, entityId, landedAt, task));
        return fallId;
    }

    /**
     * Stops an in-flight fall immediately - despawns the client-side entity
     * and neither a landed-block change nor {@code onLanded} ever fires. A
     * no-op if it already landed (or {@code fallId} is unrecognized).
     * <p>
     * Must also revert the fake barrier {@link #spawn} already painted at
     * {@code landedAt} the instant the fall started (see its own Javadoc) -
     * leaving it behind orphans an invisible-to-everyone-else but very real,
     * permanent collision box at that spot for the owner, since nothing else
     * ever revisits a fall that never made it into {@code OreCubeService}'s
     * own tracking (its reconcile/despawn logic only ever knows about
     * cubes that actually landed).
     */
    public void cancel(UUID fallId) {
        Fall fall = falls.remove(fallId);
        if (fall == null) {
            return;
        }
        fall.task().cancel();
        PacketEntityManager.destroyEntity(fall.owner(), fall.entityId());
        fall.owner().sendBlockChange(fall.landedAt(), Material.AIR.createBlockData());
    }
}
