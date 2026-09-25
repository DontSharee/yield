package me.dontshare.yieldcore.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets other plugins find out when a player left-clicks (attacks) or
 * right-clicks (interacts with) a fake, client-side-only entity - one that
 * was spawned via {@link PacketEntityManager}/{@link BlockDisplayManager}
 * (or a player-shaped NPC built directly on top of those, e.g.
 * yield-tutorial's guide) but has no real server-side entity or hitbox for
 * a normal Bukkit event to fire against.
 * <p>
 * Left-click reads the raw {@code ServerboundAttackPacket} the client sends
 * on attack (Minecraft 26.1+'s dedicated attack packet - see
 * {@link WrapperPlayClientAttack}). Right-click reads the separate
 * {@code ServerboundInteractPacket} (see {@link WrapperPlayClientInteractEntity}),
 * accepting either its {@code INTERACT} or {@code INTERACT_AT} action - a
 * normal crosshair right-click on a rendered entity sends {@code INTERACT_AT}
 * (it carries the precise hit position on the entity's hitbox), not the
 * bare {@code INTERACT} variant; treating only {@code INTERACT} as "a right-
 * click happened" (an earlier version of this class did) silently drops
 * every real click. The client sends exactly one of the two per interaction,
 * never both, so accepting either never double-dispatches a single click.
 * Both dispatch to whichever fake entity id they name, if any caller has
 * registered one.
 * <p>
 * Packets arrive off the main thread; every registered callback is
 * marshalled onto the main thread before running, so callers never need to
 * worry about thread-safety themselves.
 */
public final class EntityClickRegistry {

    private static final Map<Integer, Consumer<Player>> attackHandlers = new ConcurrentHashMap<>();
    private static final Map<Integer, Consumer<Player>> interactHandlers = new ConcurrentHashMap<>();

    private EntityClickRegistry() {
    }

    /** Call once, from {@code YieldCore#onEnable}. */
    public static void install(JavaPlugin plugin) {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
                    onAttack(event);
                } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                    onInteract(event);
                }
            }

            private void onAttack(PacketReceiveEvent event) {
                if (!(event.getPlayer() instanceof Player player)) {
                    return;
                }
                int entityId = new WrapperPlayClientAttack(event).getEntityId();
                dispatch(plugin, attackHandlers, entityId, player);
            }

            private void onInteract(PacketReceiveEvent event) {
                if (!(event.getPlayer() instanceof Player player)) {
                    return;
                }
                WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
                // One right-click is up to FOUR packets: the client sends an
                // INTERACT_AT then an INTERACT for the main hand, and - since
                // a fake entity never "consumes" the click client-side - the
                // same pair again for the off hand. Handing every one of them
                // on ran each handler up to four times: an upgrade station
                // bought up to four levels per click. Exactly one survives:
                // the main hand's INTERACT.
                if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.INTERACT
                        || wrapper.getHand() != com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND) {
                    return;
                }
                dispatch(plugin, interactHandlers, wrapper.getEntityId(), player);
            }
        });
    }

    /**
     * Players with a click of each kind already queued for the main thread.
     * A vanilla client sends at most one attack and one use per tick; a
     * hacked one can send hundreds a second, each of which became its own
     * task. One queued per player per kind loses nothing real.
     */
    private static final Set<UUID> attackQueued = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> interactQueued = ConcurrentHashMap.newKeySet();

    private static void dispatch(JavaPlugin plugin, Map<Integer, Consumer<Player>> handlers, int entityId, Player player) {
        Consumer<Player> handler = handlers.get(entityId);
        if (handler == null) {
            return;
        }
        Set<UUID> queued = handlers == attackHandlers ? attackQueued : interactQueued;
        UUID playerId = player.getUniqueId();
        if (!queued.add(playerId)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            queued.remove(playerId);
            handler.accept(player);
        });
    }

    /** Generous next to vanilla's 3-block entity reach: these hitboxes are big, and it only has to stop clicks from across the map. */
    public static final double DEFAULT_REACH = 10.0;

    /**
     * {@code handler}, but only for a click from within {@code reach} of
     * {@code at}. A fake entity's id reaches every client that ever saw it,
     * and nothing server-side checks where an attack on one came from - a
     * modified client could buy upgrades or hatch at a station from anywhere
     * on the map. Use for anything that stands in one place.
     */
    public static Consumer<Player> inReach(Location at, double reach, Consumer<Player> handler) {
        double reachSquared = reach * reach;
        return player -> {
            Location eye = player.getEyeLocation();
            if (eye.getWorld() == at.getWorld() && eye.distanceSquared(at) <= reachSquared) {
                handler.accept(player);
            }
        };
    }

    public static Consumer<Player> inReach(Location at, Consumer<Player> handler) {
        return inReach(at, DEFAULT_REACH, handler);
    }

    /** Registers a callback for left-clicks (attacks) on a specific fake entity id - call {@link #unregister} once it despawns. */
    public static void register(int entityId, Consumer<Player> onClick) {
        attackHandlers.put(entityId, onClick);
    }

    public static void unregister(int entityId) {
        attackHandlers.remove(entityId);
    }

    /** Registers a callback for right-clicks (interacts) on a specific fake entity id - call {@link #unregisterInteract} once it despawns. */
    public static void registerInteract(int entityId, Consumer<Player> onInteract) {
        interactHandlers.put(entityId, onInteract);
    }

    public static void unregisterInteract(int entityId) {
        interactHandlers.remove(entityId);
    }
}
