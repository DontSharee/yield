package me.dontshare.yieldachievements.potion;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Drinking a potion either extends an already-running effect of the exact
 * same (stat, multiplier) pair, or starts a new one running alongside
 * whatever else is already active - a player can have any number of
 * different potions active at once (different stats, or the same stat at
 * different strengths), each on its own independent timer.
 */
public final class PotionService {

    public record ActivePotion(PotionStat stat, double multiplier, long remainingSeconds) {
    }

    private final PlayerDataStore<PackPlayerProfile> store;

    public PotionService(PlayerDataStore<PackPlayerProfile> store) {
        this.store = store;
    }

    /** Applies {@code potionId}'s effect - null (a no-op) if it isn't a valid potion id. */
    public PotionDefinition consume(Player player, String potionId) {
        PotionDefinition def = PotionDefinition.parse(potionId);
        if (def == null) {
            return null;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        String key = def.stackKey();
        long now = System.currentTimeMillis();
        long currentExpiry = profile.getActivePotionExpiryMillis().getOrDefault(key, 0L);
        long base = Math.max(now, currentExpiry);
        profile.getActivePotionExpiryMillis().put(key, base + def.durationSeconds() * 1000L);
        store.save(player.getUniqueId());
        return def;
    }

    public double multiplierFor(Player player, PotionStat stat) {
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        return profile == null ? 1.0 : multiplierFor(profile, stat);
    }

    /**
     * The product of every currently-active potion targeting {@code stat} -
     * 1.0 if none. Lazily prunes expired entries as a side effect (this runs
     * on hot paths - every damage/coin/roll calculation - so it deliberately
     * does NOT save afterward; the removal is already correct in memory for
     * every subsequent call this session, and persists next time the
     * profile saves for any other reason).
     */
    public double multiplierFor(PackPlayerProfile profile, PotionStat stat) {
        long now = System.currentTimeMillis();
        double total = 1.0;
        Iterator<Map.Entry<String, Long>> it = profile.getActivePotionExpiryMillis().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() <= now) {
                it.remove();
                continue;
            }
            ParsedKey parsed = ParsedKey.parse(entry.getKey());
            if (parsed != null && parsed.stat() == stat) {
                total *= parsed.multiplier();
            }
        }
        return total;
    }

    /** Every still-active potion, for the /potions screen - also prunes expired entries. */
    public List<ActivePotion> activePotions(PackPlayerProfile profile) {
        long now = System.currentTimeMillis();
        List<ActivePotion> active = new ArrayList<>();
        Iterator<Map.Entry<String, Long>> it = profile.getActivePotionExpiryMillis().entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            long remainingMillis = entry.getValue() - now;
            if (remainingMillis <= 0) {
                it.remove();
                continue;
            }
            ParsedKey parsed = ParsedKey.parse(entry.getKey());
            if (parsed != null) {
                active.add(new ActivePotion(parsed.stat(), parsed.multiplier(), remainingMillis / 1000L));
            }
        }
        active.sort((a, b) -> Long.compare(b.remainingSeconds(), a.remainingSeconds()));
        return active;
    }

    private record ParsedKey(PotionStat stat, double multiplier) {
        static ParsedKey parse(String key) {
            int split = key.lastIndexOf('_');
            if (split <= 0) {
                return null;
            }
            try {
                return new ParsedKey(PotionStat.valueOf(key.substring(0, split)), Double.parseDouble(key.substring(split + 1)));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }
}
