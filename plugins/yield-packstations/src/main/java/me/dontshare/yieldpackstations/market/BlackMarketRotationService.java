package me.dontshare.yieldpackstations.market;

import me.dontshare.yieldpackstations.data.PackStationContentLoader.PackStationContent;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * The black market's single live pack - a GLOBAL rotation shared by every
 * player (unlike the flat shop's own {@code ShopStockService}, which rolls
 * independently per player), purely derived from the current wall-clock
 * cycle, nothing persisted. Mirrors {@code ShopStockService#currentCycleId}/
 * {@code roll} exactly, except the seed is {@code Random(cycleId)} alone -
 * no player UUID mixed in - so every player resolves the exact same pack for
 * the exact same cycle, and a server restart changes nothing (the cycle id
 * is recomputed from the clock, not read back from anywhere).
 */
public final class BlackMarketRotationService {

    private record Cached(long cycleId, String packId) {
    }

    private final Supplier<PackStationContent> content;
    private volatile Cached cache;

    public BlackMarketRotationService(Supplier<PackStationContent> content) {
        this.content = content;
    }

    public long currentCycleId() {
        return System.currentTimeMillis() / content.get().blackMarketConfig().resetIntervalMillis();
    }

    /** Which pack id is live right now - null if the pool is empty/unconfigured. */
    public String currentPackId() {
        long cycleId = currentCycleId();
        Cached cached = cache;
        if (cached != null && cached.cycleId() == cycleId) {
            return cached.packId();
        }
        List<String> pool = content.get().blackMarketConfig().eligiblePackIds();
        if (pool.isEmpty()) {
            return null;
        }
        Random rng = new Random(cycleId);
        String picked = pool.get(rng.nextInt(pool.size()));
        cache = new Cached(cycleId, picked);
        return picked;
    }
}
