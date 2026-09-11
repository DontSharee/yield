package me.dontshare.yieldpacks.fusion;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.event.PetFusedEvent;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Background auto-fuse loop - mirrors {@code PackOpenService.autoOpenTick}'s
 * exact shape: once per second, iterate online players, skip anyone without
 * a cached profile/the toggle/the permission, and run the same {@link
 * FusionService#fuseAll} the manual "Fuse All" button uses (one shared
 * execution path, firing the same per-result {@link PetFusedEvent}s) for
 * everyone who qualifies. Permission is checked every tick, not just at
 * toggle time, so a revoked permission takes effect immediately.
 */
public final class AutoFuseService {

    private static final long TICK_INTERVAL = 20L; // 1 second
    private static final String PERMISSION = "yieldpacks.autofuse";

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final FusionService fusionService;

    public AutoFuseService(JavaPlugin plugin, PlayerDataStore<PackPlayerProfile> store, FusionService fusionService) {
        this.plugin = plugin;
        this.store = store;
        this.fusionService = fusionService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(PERMISSION)) {
                continue;
            }
            PackPlayerProfile profile = store.getCached(player.getUniqueId());
            if (profile == null || !profile.isAutoFuseEnabled()) {
                continue;
            }
            List<String> results = fusionService.fuseAll(profile, FusionService.FUSE_ALL_CAP);
            if (results.isEmpty()) {
                continue;
            }
            store.save(player.getUniqueId());
            for (String resultId : results) {
                Bukkit.getPluginManager().callEvent(new PetFusedEvent(player, resultId));
            }
        }
    }
}
