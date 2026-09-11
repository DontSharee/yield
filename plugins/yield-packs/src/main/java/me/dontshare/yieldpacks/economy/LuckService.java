package me.dontshare.yieldpacks.economy;

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
                .filter(pack -> isFullyCollected(profile, pack.id(), pack.pool().size()))
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

    private boolean isFullyCollected(PackPlayerProfile profile, String packId, int poolSize) {
        Set<String> collected = profile.getPackCollectionProgress().get(packId);
        return collected != null && collected.size() >= poolSize;
    }
}
