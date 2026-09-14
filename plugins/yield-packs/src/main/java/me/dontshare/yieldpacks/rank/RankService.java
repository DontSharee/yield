package me.dontshare.yieldpacks.rank;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Rank cost curve, the diamond-income bonus it grants, and the actual purchase
 * flow - mirrors yield-rebirth's own RebirthService in shape (same
 * exponential cost curve, same "bulk-buy as many as affordable" preview/max
 * pattern), except the currency is diamonds, not coins, and there's no reset of
 * anything - rank just keeps climbing forever.
 */
public final class RankService {

    /** Same reasoning as RebirthService's own cap - coins/diamonds are unbounded (BigInteger), so a misconfigured near-1.0 growth rate against a huge balance could otherwise loop essentially forever. */
    private static final int MAX_RANKS_PER_PREVIEW = 100_000;

    private static final long BASE_COST_DIAMONDS = 1_000L;
    private static final double COST_GROWTH = 1.15;
    /** +2% diamonds per rank, additive - same shape every other "1.0 + Σ" multiplier in this codebase uses. */
    private static final double DIAMOND_BONUS_PER_RANK = 0.02;

    public record RankPreview(int available, BigInteger totalCost) {
    }

    private final PlayerDataStore<PackPlayerProfile> store;

    public RankService(PlayerDataStore<PackPlayerProfile> store) {
        this.store = store;
    }

    /** {@code BASE_COST_DIAMONDS * COST_GROWTH^rank} - the diamond cost to go from {@code rank} to {@code rank + 1}. */
    public BigInteger costForRank(int rank) {
        BigDecimal cost = BigDecimal.valueOf(BASE_COST_DIAMONDS).multiply(BigDecimal.valueOf(COST_GROWTH).pow(rank));
        return cost.setScale(0, RoundingMode.HALF_UP).toBigInteger();
    }

    /** The permanent diamond-income multiplier from rank - consumed via YieldPacks#diamondMultiplier's own composable registry. */
    public double diamondMultiplier(PackPlayerProfile profile) {
        return diamondMultiplierForRank(profile.getRank());
    }

    /** Same formula as {@link #diamondMultiplier}, for an arbitrary rank rather than a real profile - lets RankupGui show what a NOT-YET-owned rank slot would grant without needing a fake profile. */
    public double diamondMultiplierForRank(int rank) {
        return 1.0 + rank * DIAMOND_BONUS_PER_RANK;
    }

    /** How many ranks the player could buy right now (from their current rank onward), and the combined cost, without applying anything. */
    public RankPreview preview(PackPlayerProfile profile) {
        int available = 0;
        BigInteger totalCost = BigInteger.ZERO;
        BigInteger remaining = profile.getDiamonds();
        while (available < MAX_RANKS_PER_PREVIEW) {
            BigInteger nextCost = costForRank(profile.getRank() + available);
            if (nextCost.compareTo(remaining) > 0) {
                break;
            }
            remaining = remaining.subtract(nextCost);
            totalCost = totalCost.add(nextCost);
            available++;
        }
        return new RankPreview(available, totalCost);
    }

    /** Buys exactly 1 rank if affordable - a no-op (empty preview) otherwise. */
    public RankPreview rankUpOnce(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        BigInteger cost = costForRank(profile.getRank());
        if (profile.getDiamonds().compareTo(cost) < 0) {
            return new RankPreview(0, BigInteger.ZERO);
        }
        profile.setDiamonds(profile.getDiamonds().subtract(cost));
        profile.setRank(profile.getRank() + 1);
        store.save(player.getUniqueId());
        return new RankPreview(1, cost);
    }

    /** Buys every rank the player can currently afford in one go, cheapest-first (since rank only ever climbs, that's simply every next rank in order). */
    public RankPreview rankUpMax(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        RankPreview preview = preview(profile);
        if (preview.available() > 0) {
            profile.setDiamonds(profile.getDiamonds().subtract(preview.totalCost()));
            profile.setRank(profile.getRank() + preview.available());
            store.save(player.getUniqueId());
        }
        return preview;
    }

    /**
     * Buys every rank from the player's current one up through (and
     * including) {@code targetRank}, only if ALL of them are affordable
     * together - used by RankupGui's progression-track slots ("buy up to
     * here"), never a partial purchase that stops short of the rank the
     * player actually clicked.
     */
    public RankPreview rankUpTo(Player player, int targetRank) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (targetRank <= profile.getRank()) {
            return new RankPreview(0, BigInteger.ZERO);
        }
        BigInteger totalCost = BigInteger.ZERO;
        for (int rank = profile.getRank(); rank < targetRank; rank++) {
            totalCost = totalCost.add(costForRank(rank));
        }
        if (profile.getDiamonds().compareTo(totalCost) < 0) {
            return new RankPreview(0, BigInteger.ZERO);
        }
        int granted = targetRank - profile.getRank();
        profile.setDiamonds(profile.getDiamonds().subtract(totalCost));
        profile.setRank(targetRank);
        store.save(player.getUniqueId());
        return new RankPreview(granted, totalCost);
    }
}
