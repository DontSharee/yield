package me.dontshare.yieldcore.teleport;

import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pending /tpa and /tpahere requests - purely in-memory (a request that
 * outlives either player's session is meaningless anyway), one at a time
 * per target (a fresh request simply replaces whatever was pending for
 * them), and auto-expiring after {@link #REQUEST_TIMEOUT_TICKS}.
 */
public final class TeleportRequestService {

    private static final long REQUEST_TIMEOUT_TICKS = 20L * 60; // 60 seconds

    private record PendingRequest(UUID requesterId, boolean here) {
    }

    private final JavaPlugin plugin;
    private final Map<UUID, PendingRequest> pendingByTarget = new ConcurrentHashMap<>();

    public TeleportRequestService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** {@code here} true = the target should teleport TO the requester (/tpahere); false = the requester teleports to the target (/tpa). */
    public void request(Player requester, Player target, boolean here) {
        UUID targetId = target.getUniqueId();
        pendingByTarget.put(targetId, new PendingRequest(requester.getUniqueId(), here));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingRequest current = pendingByTarget.get(targetId);
            if (current != null && current.requesterId().equals(requester.getUniqueId())) {
                pendingByTarget.remove(targetId, current);
            }
        }, REQUEST_TIMEOUT_TICKS);
    }

    /** Teleports and messages both sides on success - true if there was a pending request to act on at all. */
    public boolean accept(Player target) {
        PendingRequest request = pendingByTarget.remove(target.getUniqueId());
        if (request == null) {
            return false;
        }
        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null) {
            target.sendMessage(Text.parse("<red>That player is no longer online.</red>"));
            return true;
        }
        if (request.here()) {
            target.teleport(requester.getLocation());
        } else {
            requester.teleport(target.getLocation());
        }
        target.playSound(target.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        target.sendMessage(Text.parse("<green>Accepted <name>'s request.</green>", Placeholder.unparsed("name", requester.getName())));
        requester.sendMessage(Text.parse("<green><name> accepted your teleport request.</green>", Placeholder.unparsed("name", target.getName())));
        return true;
    }

    /** Messages the requester (if still online) on success - true if there was a pending request to deny at all. */
    public boolean deny(Player target) {
        PendingRequest request = pendingByTarget.remove(target.getUniqueId());
        if (request == null) {
            return false;
        }
        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester != null) {
            requester.sendMessage(Text.parse("<red><name> denied your teleport request.</red>", Placeholder.unparsed("name", target.getName())));
        }
        return true;
    }

    public void clear(UUID playerId) {
        pendingByTarget.remove(playerId);
    }
}
