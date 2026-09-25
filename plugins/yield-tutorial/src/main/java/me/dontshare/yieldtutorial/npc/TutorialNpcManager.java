package me.dontshare.yieldtutorial.npc;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Owns the live (spawned) state of the tutorial's one Guide NPC - a fake
 * player-type entity (see {@link PacketEntityManager}), re-diffed every
 * tick against whichever online players are both currently tutorial-active
 * ({@link #setVisibilityFilter}) and within {@link #VISIBLE_RADIUS} of it,
 * rather than tracked via join/quit/move events - simpler, and self-heals
 * for teleports and disconnects for free. Ported from the same pattern
 * tideborn's quest {@code NpcManager} used, simplified down to exactly one
 * NPC (no per-island tracking, no database-backed roster) since the
 * tutorial only ever needs the one guide.
 * <p>
 * A single {@code entityId}/{@code npcId} pair is reused for every viewer -
 * safe because {@link PacketEntityManager#nextEntityId} draws from the same
 * global, never-reused counter the real server uses, and because tab-list/
 * entity packets are unicast per connection, so two different players
 * being shown "the same" fake IDs never collide client-side.
 */
public final class TutorialNpcManager {

    private static final double VISIBLE_RADIUS = 48.0;
    private static final long REFRESH_INTERVAL_TICKS = 20L;
    // How long the tab-list entry stays before removal - long enough for the
    // client to resolve/cache the skin from it after the spawn packet.
    private static final long UNLIST_DELAY_TICKS = 40L;

    private final JavaPlugin plugin;
    private final String npcName;
    private final Location location;
    private final int entityId = PacketEntityManager.nextEntityId();
    private final UUID npcId = UUID.randomUUID();
    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    /** Which players should see the NPC at all right now (mid-tutorial, not skipped/completed) - supplied by {@code TutorialService}. */
    private volatile Predicate<Player> shouldShowFor = player -> false;

    public TutorialNpcManager(JavaPlugin plugin, String npcName, Location location) {
        this.plugin = plugin;
        this.npcName = npcName;
        this.location = location;
    }

    public void setVisibilityFilter(Predicate<Player> shouldShowFor) {
        this.shouldShowFor = shouldShowFor;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, REFRESH_INTERVAL_TICKS);
    }

    public int entityId() {
        return entityId;
    }

    /** Force-hides the NPC for one specific player immediately (they just advanced/skipped/completed) rather than waiting for the next tick's diff. */
    public void hideFor(Player player) {
        if (viewers.remove(player.getUniqueId())) {
            PacketEntityManager.destroyEntity(player, entityId);
        }
    }

    /**
     * Drops a quitting player without sending anything. Left in the set, a
     * quick relog (a duplicate-login kick is the same tick) reads as
     * "already showing" and the fresh client never gets the NPC spawned.
     */
    public void forget(UUID playerId) {
        viewers.remove(playerId);
    }

    private void tick() {
        double radiusSquared = VISIBLE_RADIUS * VISIBLE_RADIUS;
        Set<UUID> desired = new HashSet<>();
        for (Player player : location.getWorld().getPlayers()) {
            if (shouldShowFor.test(player) && player.getLocation().distanceSquared(location) <= radiusSquared) {
                desired.add(player.getUniqueId());
            }
        }

        for (UUID playerId : desired) {
            if (viewers.add(playerId)) {
                Player viewer = Bukkit.getPlayer(playerId);
                if (viewer != null) {
                    spawnFor(viewer);
                }
            }
        }

        viewers.removeIf(playerId -> {
            if (desired.contains(playerId)) {
                return false;
            }
            Player viewer = Bukkit.getPlayer(playerId);
            if (viewer != null) {
                PacketEntityManager.destroyEntity(viewer, entityId);
            }
            return true;
        });
    }

    private void spawnFor(Player viewer) {
        // Tab-list entry + spawn bundled together so the client never has a
        // frame where the entity exists without a profile to resolve a skin
        // from - same pattern FancyNpcs uses for its player-type NPCs.
        PacketEntityManager.beginBundle(viewer);
        PacketEntityManager.addPlayerInfo(viewer, npcId, npcName);
        PacketEntityManager.spawnEntity(viewer, entityId, npcId, EntityTypes.PLAYER, location);
        PacketEntityManager.endBundle(viewer);

        PacketEntityManager.setCustomName(viewer, entityId, Text.parse(npcName), true);
        PacketEntityManager.setMaxHealth(viewer, entityId, 1.0); // cosmetic-only NPC, not a real combatant

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (viewer.isOnline()) {
                PacketEntityManager.removePlayerInfo(viewer, npcId);
            }
        }, UNLIST_DELAY_TICKS);
    }
}
