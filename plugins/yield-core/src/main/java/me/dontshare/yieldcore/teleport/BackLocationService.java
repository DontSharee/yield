package me.dontshare.yieldcore.teleport;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers where a player teleported FROM, for {@code /back}. Hooks the
 * generic {@link PlayerTeleportEvent} rather than any one specific command
 * (home/spawn/tpa/fast-travel/warps/ender pearls/...) - every teleport in
 * the game already fires this one real Bukkit event no matter which plugin
 * caused it, so nothing else needs its own "record my origin" call.
 */
public final class BackLocationService implements Listener {

    private final Map<UUID, Location> lastLocation = new ConcurrentHashMap<>();
    // Set right before /back's own teleport - without this, /back's own
    // PlayerTeleportEvent would immediately overwrite the spot it just
    // teleported FROM (the origin, which is the destination player wants
    // to leave), making a second /back bounce right back there instead of
    // going anywhere useful.
    private final Set<UUID> suppressNextRecord = ConcurrentHashMap.newKeySet();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (suppressNextRecord.remove(id)) {
            return;
        }
        lastLocation.put(id, event.getFrom());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        lastLocation.put(event.getEntity().getUniqueId(), event.getEntity().getLocation());
    }

    /** Null if nothing's recorded for this player yet. */
    public boolean teleportBack(Player player) {
        Location location = lastLocation.get(player.getUniqueId());
        if (location == null) {
            return false;
        }
        suppressNextRecord.add(player.getUniqueId());
        player.teleport(location);
        return true;
    }
}
