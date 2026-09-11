package me.dontshare.yieldcore.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
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
                WrapperPlayClientInteractEntity.InteractAction action = wrapper.getAction();
                if (action != WrapperPlayClientInteractEntity.InteractAction.INTERACT
                        && action != WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
                    return;
                }
                dispatch(plugin, interactHandlers, wrapper.getEntityId(), player);
            }
        });
    }

    private static void dispatch(JavaPlugin plugin, Map<Integer, Consumer<Player>> handlers, int entityId, Player player) {
        Consumer<Player> handler = handlers.get(entityId);
        if (handler != null) {
            Bukkit.getScheduler().runTask(plugin, () -> handler.accept(player));
        }
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
