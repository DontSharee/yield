package me.dontshare.yieldcore.fakeblock;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets other plugins find out when a player left-clicks a fake, per-player
 * block - one that only exists client-side via {@link Player#sendBlockChange}
 * (see {@link FakeFallingBlock}), never as a real block the server knows
 * about. A real block would fire {@code BlockBreakEvent} normally; a fake
 * one never will, since the server-side world at that position is
 * untouched.
 * <p>
 * The click itself is detected by reading the client's raw arm-swing packet
 * (sent on every left-click, hit or miss, regardless of range) and doing a
 * server-side ray/AABB test against every fake block currently registered
 * for that specific player, out to {@link #RAYCAST_RANGE} - deliberately
 * NOT vanilla's own block-targeting/interaction range (a real left-click on
 * a REAL block is capped around 4.5-6 blocks), since these blocks aren't
 * real and there's no reason a fake target has to obey that same limit.
 * {@link #RAYCAST_RANGE} is set effectively unbounded (far larger than any
 * real zone) rather than to some other fixed distance - a caller like
 * yield-zones only ever registers fake blocks within a bounded zone region
 * in the first place, so that region is already the real limit on how far
 * away a clickable cube can be, not this constant.
 * The closest intersected registration (if any) wins, mirroring "you can
 * only click what you're actually looking at."
 * <p>
 * Right-clicking one of these fake blocks is a separate problem: the
 * client's own raycast sees the fake block as solid, but the server's real
 * world at that position is still air - which is replaceable - so the
 * vanilla server would happily place a real block (or otherwise interact,
 * e.g. till soil, open a door) exactly where the fake block only appears
 * to be. Left unhandled, right-clicking a fake block while holding a
 * placeable item spawns a real block that outlives the fake one entirely.
 * This is fixed by reading the client's raw {@code PLAYER_BLOCK_PLACEMENT}
 * packet (the packet vanilla sends for nearly every right-click-on-a-block
 * interaction, not just placing) and cancelling it, but ONLY when its
 * reported position matches a fake block currently registered for that
 * exact player - every other block placement/interaction in the world is
 * left untouched.
 */
public final class FakeBlockClickRegistry {

    // Effectively unbounded - see class Javadoc for why a zone's own size,
    // not this constant, is meant to be the real limit on reach.
    private static final double RAYCAST_RANGE = 512.0;

    private record Key(String world, int x, int y, int z) {
    }

    /**
     * Keyed by player first, so a swing only ever examines that player's own
     * handful of fake blocks.
     * <p>
     * This used to be one flat map of every fake block on the server, with
     * the per-player check made inside the scan loop - so every arm-swing
     * from every player walked every other player's registrations. In a game
     * where players hold left-click continuously that is the hottest path in
     * the codebase, and it grew with the square of the player count: a zone
     * wall alone registers one entry per block of its volume, per player.
     */
    /**
     * A click handler and the size of the box it answers for - 1 for an
     * ordinary block, more for a giant cube, whose visible body hangs past
     * its own block on every side and has to be clickable there too.
     */
    private record Registration(Consumer<Player> onClick, double size) {
    }

    private static final Map<UUID, Map<Key, Registration>> handlers = new ConcurrentHashMap<>();

    private FakeBlockClickRegistry() {
    }

    /** Call once, from {@code YieldCore#onEnable}. */
    public static void install(JavaPlugin plugin) {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
                    onSwing(event);
                } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                    onBlockPlacement(event);
                }
            }

            private void onSwing(PacketReceiveEvent event) {
                if (!(event.getPlayer() instanceof Player player)) {
                    return;
                }
                // Nearly every swing on the server comes from someone with no
                // fake blocks registered at all. Checking that here, off the
                // main thread, keeps those from each costing a scheduled task.
                if (!handlers.containsKey(player.getUniqueId())) {
                    return;
                }
                // The raycast itself calls live Bukkit API (player
                // location/world), so that (and the handler call) are
                // deferred to the main thread like every other packet
                // handler in this codebase.
                Bukkit.getScheduler().runTask(plugin, () -> handleSwing(player));
            }

            private void onBlockPlacement(PacketReceiveEvent event) {
                if (!(event.getPlayer() instanceof Player player)) {
                    return;
                }
                Map<Key, Registration> own = handlers.get(player.getUniqueId());
                if (own == null) {
                    return;
                }
                Vector3i pos = new WrapperPlayClientPlayerBlockPlacement(event).getBlockPosition();
                if (own.containsKey(new Key(player.getWorld().getName(), pos.x, pos.y, pos.z))) {
                    event.setCancelled(true);
                }
            }
        });

        // Nothing guarantees a caller unregisters everything before a player
        // leaves, and a stale entry would otherwise be kept alive forever by
        // this static map, along with the Consumer's captured state.
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                handlers.remove(event.getPlayer().getUniqueId());
            }
        }, plugin);
    }

    private static void handleSwing(Player player) {
        Map<Key, Registration> own = handlers.get(player.getUniqueId());
        if (own == null || own.isEmpty()) {
            return;
        }
        Key hit = raycast(player, own);
        if (hit == null) {
            return;
        }
        Registration registration = own.get(hit);
        if (registration != null) {
            registration.onClick().accept(player);
        }
    }

    /** The closest fake block registered to {@code player} that their current look direction intersects within {@link #RAYCAST_RANGE}, or null. */
    private static Key raycast(Player player, Map<Key, Registration> own) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        String worldName = eye.getWorld().getName();

        Key closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Map.Entry<Key, Registration> entry : own.entrySet()) {
            Key key = entry.getKey();
            if (!key.world().equals(worldName)) {
                continue;
            }
            Double distance = intersectDistance(eye, direction, key.x(), key.y(), key.z(),
                    entry.getValue().size(), RAYCAST_RANGE);
            if (distance != null && distance < closestDistance) {
                closestDistance = distance;
                closest = key;
            }
        }
        if (closest == null) {
            return null;
        }
        // Fake blocks exist only client-side, so the maths above sees
        // straight through real ones. A real block in front of the fake one
        // (a wall, or a world boss's barrier hitbox) is what the player
        // actually clicked - without this, clicking a boss also "clicked"
        // the ore cube behind it, which pulled the pets straight back off.
        var realHit = eye.getWorld().rayTraceBlocks(eye, direction, closestDistance,
                org.bukkit.FluidCollisionMode.NEVER, true);
        if (realHit != null && realHit.getHitPosition().distance(eye.toVector()) < closestDistance - 1.0E-3) {
            return null;
        }
        return closest;
    }

    /**
     * Standard slab-method ray/axis-aligned-bounding-box test against a
     * {@code size}-block cube standing on block {@code (x, y, z)}'s floor
     * and centred on its column - the same footprint
     * {@link me.dontshare.yieldcore.packet.BlockDisplayManager#setBlockSize}
     * renders, so what you can click is exactly what you can see. At size 1
     * that is just the block itself. Returns the ray's entry distance if it
     * hits within {@code range}, or null if it misses.
     * <p>
     * Public so anything else that needs "which of these cubes am I looking
     * at" (the ore-cube highlight) asks the same question the same way
     * rather than keeping a second copy of the maths.
     */
    public static Double intersectDistance(Location eye, Vector direction, int x, int y, int z,
                                           double size, double range) {
        double tMin = 0.0;
        double tMax = range;

        double[] origin = {eye.getX(), eye.getY(), eye.getZ()};
        double[] dir = {direction.getX(), direction.getY(), direction.getZ()};
        double inset = 0.5 - size / 2.0;
        double[] boxMin = {x + inset, y, z + inset};
        double[] boxMax = {boxMin[0] + size, y + size, boxMin[2] + size};

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1e-9) {
                if (origin[axis] < boxMin[axis] || origin[axis] > boxMax[axis]) {
                    return null;
                }
                continue;
            }
            double t1 = (boxMin[axis] - origin[axis]) / dir[axis];
            double t2 = (boxMax[axis] - origin[axis]) / dir[axis];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return null;
            }
        }
        return tMin;
    }

    /** Registers a callback for {@code player} left-clicking the fake block at {@code location} - call {@link #unregister} once it's gone. */
    public static void register(Player player, Location location, Consumer<Player> onClick) {
        register(player, location, 1.0, onClick);
    }

    /** {@link #register(Player, Location, Consumer)} for a cube {@code size} blocks big, clickable across its whole visible body. */
    public static void register(Player player, Location location, double size, Consumer<Player> onClick) {
        handlers.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(keyFor(location), new Registration(onClick, size));
    }

    public static void unregister(Player player, Location location) {
        handlers.computeIfPresent(player.getUniqueId(), (ignored, own) -> {
            own.remove(keyFor(location));
            // Dropping the empty map keeps the fast "has this player got
            // anything at all" check in onSwing meaningful.
            return own.isEmpty() ? null : own;
        });
    }

    private static Key keyFor(Location location) {
        return new Key(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
