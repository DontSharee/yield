package me.dontshare.yieldrebirth;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * Rebirth cost curve and affordability math. Rebirthing only deducts the
 * cost of the rebirth(s) actually taken from the player's coins - it never
 * resets the balance to zero, and never touches the bag or equipped pets.
 */
public final class RebirthService {

    public record RebirthPreview(int available, long totalCost) {
    }

    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final double baseCost;
    private final double growth;

    public RebirthService(PlayerDataStore<PackPlayerProfile> playerStore, FileConfiguration config) {
        this.playerStore = playerStore;
        this.baseCost = config.getDouble("base-cost", 10_000);
        this.growth = config.getDouble("growth", 1.5);
    }

    public long requiredCoins(int rebirthNumber) {
        return Math.round(baseCost * Math.pow(growth, rebirthNumber));
    }

    /** The permanent coin-income multiplier from rebirths: +1% per rebirth. */
    public double coinMultiplier(PackPlayerProfile profile) {
        return 1.0 + profile.getRebirths() * 0.01;
    }

    /** How many rebirths the player could take right now, and their combined cost, without applying anything. */
    public RebirthPreview preview(PackPlayerProfile profile) {
        int available = 0;
        long totalCost = 0;
        long remaining = profile.getCoins();
        while (true) {
            long nextCost = requiredCoins(profile.getRebirths() + available);
            if (nextCost > remaining) {
                break;
            }
            remaining -= nextCost;
            totalCost += nextCost;
            available++;
        }
        return new RebirthPreview(available, totalCost);
    }

    /** Deducts only the cost of the rebirth(s) taken - never a full balance reset. Returns what was applied. */
    public RebirthPreview rebirth(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        RebirthPreview preview = preview(profile);
        if (preview.available() > 0) {
            profile.setCoins(profile.getCoins() - preview.totalCost());
            profile.setRebirths(profile.getRebirths() + preview.available());
            playerStore.save(player.getUniqueId());
        }
        return preview;
    }
}
