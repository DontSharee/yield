package me.dontshare.yieldcore.fakeblock;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgePlayerDigging;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Lets other plugins know when a player finishes REALLY holding down mining
 * on a fake, per-player block - one that only exists client-side via
 * {@link Player#sendBlockChange} (see {@link FakeFallingBlock}, and
 * {@link FakeBlockClickRegistry} for the single-swing equivalent of this).
 * A real block would fire {@code BlockBreakEvent} normally with correct
 * tool-tier/hardness timing handled by vanilla for free; a fake one never
 * will, since the server-side world at that position is untouched - so this
 * reads the client's own raw digging packets instead and trusts whatever
 * timing the client itself decided (it already knows the fake block's type
 * from the {@code sendBlockChange} that painted it, so it renders the same
 * crack-progress animation and takes the same real amount of time a real
 * block of that type would, tool included) rather than re-implementing
 * vanilla's hardness/tool-efficiency formula server-side.
 * <p>
 * Every digging packet aimed at a currently-registered position is
 * cancelled outright (never reaching vanilla's own dig handling, which would
 * find real air there and could fight the illusion) and acknowledged back
 * to the client with the same sequence number, so its predicted block state
 * is never rubber-banded. Only {@link DiggingAction#FINISHED_DIGGING}
 * actually invokes the registered callback.
 * <p>
 * Trust note: since the server never verifies hardness/tool-tier itself
 * (there's no real block to check it against), a modified client could in
 * principle send a premature FINISHED_DIGGING. This matches the trust level
 * {@link FakeBlockClickRegistry} already extends to a client's reported
 * swings - acceptable for this server, not a hardened anti-cheat surface.
 */
public final class FakeBlockDigRegistry {

    private record Key(UUID playerId, String world, int x, int y, int z) {
    }

    private static final Map<Key, Consumer<Player>> handlers = new ConcurrentHashMap<>();

    private FakeBlockDigRegistry() {
    }

    /** Call once, from {@code YieldCore#onEnable}. */
    public static void install(JavaPlugin plugin) {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() != PacketType.Play.Client.PLAYER_DIGGING) {
                    return;
                }
                if (!(event.getPlayer() instanceof Player player)) {
                    return;
                }
                WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
                Vector3i pos = packet.getBlockPosition();
                Key key = new Key(player.getUniqueId(), player.getWorld().getName(), pos.x, pos.y, pos.z);
                Consumer<Player> handler = handlers.get(key);
                if (handler == null) {
                    return;
                }

                // Never let this reach vanilla's own dig handling - the real
                // block there is air, and vanilla could otherwise try to
                // "correct" the client back to that, undoing the illusion.
                event.setCancelled(true);
                DiggingAction action = packet.getAction();
                int sequence = packet.getSequence();
                User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                user.sendPacket(new WrapperPlayServerAcknowledgePlayerDigging(action, true, pos, sequence));

                if (action == DiggingAction.FINISHED_DIGGING) {
                    Bukkit.getScheduler().runTask(plugin, () -> handler.accept(player));
                }
            }
        });
    }

    /** Registers a callback for {@code player} finishing a real hold-to-mine dig on the fake block at {@code location} - call {@link #unregister} once it's gone. */
    public static void register(Player player, Location location, Consumer<Player> onFinishedDigging) {
        handlers.put(keyFor(player, location), onFinishedDigging);
    }

    public static void unregister(Player player, Location location) {
        handlers.remove(keyFor(player, location));
    }

    private static Key keyFor(Player player, Location location) {
        return new Key(player.getUniqueId(), location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
