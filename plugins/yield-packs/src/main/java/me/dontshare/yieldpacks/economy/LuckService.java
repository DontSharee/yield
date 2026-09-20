package me.dontshare.yieldpacks.economy;

import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.data.PackPoolEntry;
import me.dontshare.yieldpacks.data.PackRegistry;
import me.dontshare.yieldpacks.player.PackPlayerProfile;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Computes the permanent luck multiplier earned from fully collecting a
 * pack's pet pool (+5% per fully-collected pack, tunable below). Computed on
 * demand from collection progress rather than cached, so it can never drift
 * out of sync with the player's actual progress.
 */
public final class LuckService {

    private static final double LUCK_PER_COMPLETED_PACK = 0.05;

    private final Supplier<PackRegistry> packRegistry;

    /** Additional luck from outside sources (e.g. yield-skilltree's LUCK_MULTIPLIER nodes, yield-teams' luck upgrade) - summed on top of collection luck. Keyed so more than one plugin can contribute at once, matching YieldPacks' own coin/damage/attack-speed provider pattern. */
    private final Map<String, Function<PackPlayerProfile, Double>> extraLuckProviders = new ConcurrentHashMap<>();

    public LuckService(Supplier<PackRegistry> packRegistry) {
        this.packRegistry = packRegistry;
    }

    public double totalLuckMultiplier(PackPlayerProfile profile) {
        long completed = packRegistry.get().all().stream()
                .filter(pack -> isFullyCollected(profile, pack))
                .count();
        double extra = 0.0;
        for (Function<PackPlayerProfile, Double> provider : extraLuckProviders.values()) {
            extra += provider.apply(profile);
        }
        return 1.0 + completed * LUCK_PER_COMPLETED_PACK + extra;
    }

    public void registerExtraLuckProvider(String key, Function<PackPlayerProfile, Double> provider) {
        extraLuckProviders.put(key, provider);
    }

    public void unregisterExtraLuckProvider(String key) {
        extraLuckProviders.remove(key);
    }

    /**
     * Whether every pet in this pack's pool has been seen.
     * <p>
     * Counts only ids that are ACTUALLY in the pool, rather than trusting
     * the size of the recorded set. A pack can hand over pets that were
     * never in its pool - a Huge (see {@code PackRollService}) or an
     * Exclusive from yield-blocktree's find-perk - and those are still
     * recorded against the pack they came from. Comparing raw sizes let one
     * of those stand in for a pet the player had never actually collected,
     * which both showed "11/10" in the Index and handed over this pack's
     * +5% completion luck for a pool that was still incomplete.
     */
    private boolean isFullyCollected(PackPlayerProfile profile, PackDefinition pack) {
        return collectedFromPool(profile, pack) >= pack.pool().size();
    }

    /** How many of {@code pack}'s own pool entries this player has seen - the number the Index shows. */
    public int collectedFromPool(PackPlayerProfile profile, PackDefinition pack) {
        Set<String> collected = profile.getPackCollectionProgress().get(pack.id());
        if (collected == null || collected.isEmpty()) {
            return 0;
        }
        int found = 0;
        for (PackPoolEntry entry : pack.pool()) {
            if (collected.contains(entry.itemId())) {
                found++;
            }
        }
        return found;
    }
}
