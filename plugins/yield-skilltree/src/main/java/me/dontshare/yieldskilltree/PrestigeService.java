package me.dontshare.yieldskilltree;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldskilltree.event.PrestigeEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.math.BigInteger;

/**
 * Prestige cost gate and reset - fully resets Coins and Rebirth count back
 * to 0 (Bag/equipped pets/fusions are never touched) in exchange for
 * Prestige Points, spent exclusively in the Prestige Upgrades tree.
 */
public final class PrestigeService {

    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private volatile int rebirthsRequired;
    private volatile long pointsPerPrestige;

    public PrestigeService(PlayerDataStore<PackPlayerProfile> playerStore, FileConfiguration config) {
        this.playerStore = playerStore;
        reload(config);
    }

    /** Re-reads the cost/reward values in place - callers (e.g. PrestigeDialog) keep the same instance across a reload. */
    public void reload(FileConfiguration config) {
        this.rebirthsRequired = config.getInt("rebirths-required", 100);
        this.pointsPerPrestige = config.getLong("points-per-prestige", 1);
    }

    public int getRebirthsRequired() {
        return rebirthsRequired;
    }

    public boolean canPrestige(PackPlayerProfile profile) {
        return profile.getRebirths() >= rebirthsRequired;
    }

    /** Returns the new Prestige Points balance, or null if {@link #canPrestige} was false. */
    public BigInteger prestige(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        if (!canPrestige(profile)) {
            return null;
        }
        profile.setCoins(BigInteger.ZERO);
        profile.setRebirths(0);
        profile.setPrestigePoints(profile.getPrestigePoints().add(BigInteger.valueOf(pointsPerPrestige)));
        profile.setPrestiges(profile.getPrestiges() + 1);
        playerStore.save(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new PrestigeEvent(player, profile.getPrestiges()));
        return profile.getPrestigePoints();
    }
}
