package me.dontshare.yieldpacks.rank;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.math.BigInteger;

/**
 * Rank is never stored directly - it's always derived fresh from
 * accumulated Stars (see {@link #rankOf}), same "never cache a derived
 * value" shape {@code MasteryService#levelOf} already established. Stars
 * only ever come from yield-quests' Rank Quests (see {@link #grantStars}) -
 * there's no purchase flow anymore. Each rank still grants the permanent
 * diamond-income multiplier automatically, plus a one-time coin/diamond
 * reward that requires an explicit sequential claim (see {@link
 * #claimNextReward}), mirroring yield-achievements' MilestoneService.
 */
public final class RankService {

    /** Safety cap on the derive-from-stars loop in {@link #rankOf}, same reasoning as the old purchase preview's own cap - a misconfigured near-1.0 growth rate against a huge Star balance could otherwise loop essentially forever. */
    private static final int MAX_RANK_ITERATIONS = 100_000;

    private static final long BASE_STARS = 5L;
    private static final double STAR_GROWTH = 1.15;
    /** +2% diamonds per rank, additive - unchanged from before, same shape every other "1.0 + Σ" multiplier in this codebase uses. */
    private static final double DIAMOND_BONUS_PER_RANK = 0.02;

    /** Placeholder linear reward formulas for the one-time per-rank claim - trivially retunable later. */
    private static final long REWARD_COINS_PER_RANK = 250L;
    private static final long REWARD_DIAMONDS_PER_RANK = 10L;

    private final PlayerDataStore<PackPlayerProfile> store;

    public RankService(PlayerDataStore<PackPlayerProfile> store) {
        this.store = store;
    }

    /** {@code BASE_STARS * STAR_GROWTH^rank} - the Stars needed to go from {@code rank} to {@code rank + 1}. */
    public long starsForRank(int rank) {
        return Math.round(BASE_STARS * Math.pow(STAR_GROWTH, rank));
    }

    /** Derives the player's current rank fresh from accumulated Stars against the cost curve - never stored. */
    public int rankOf(PackPlayerProfile profile) {
        long stars = profile.getStars();
        int rank = 0;
        while (rank < MAX_RANK_ITERATIONS && stars >= starsForRank(rank)) {
            stars -= starsForRank(rank);
            rank++;
        }
        return rank;
    }

    /** How much Stars is banked toward the CURRENT rank (i.e. since the last rank-up), for a progress display. */
    public long starsIntoCurrentRank(PackPlayerProfile profile) {
        long stars = profile.getStars();
        int rank = rankOf(profile);
        for (int i = 0; i < rank; i++) {
            stars -= starsForRank(i);
        }
        return stars;
    }

    /** The permanent diamond-income multiplier from rank - consumed via YieldPacks#diamondMultiplier's own composable registry. */
    public double diamondMultiplier(PackPlayerProfile profile) {
        return diamondMultiplierForRank(rankOf(profile));
    }

    /** Same formula as {@link #diamondMultiplier}, for an arbitrary rank rather than a real profile - lets RankupGui show what a NOT-YET-reached rank slot would grant. */
    public double diamondMultiplierForRank(int rank) {
        return 1.0 + rank * DIAMOND_BONUS_PER_RANK;
    }

    /** Adds Stars, saves, and flashes a quiet action-bar line if it crossed a rank - same shape as MasteryService#grantXp. */
    public void grantStars(Player player, long amount) {
        if (amount <= 0) {
            return;
        }
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        int before = rankOf(profile);
        profile.setStars(profile.getStars() + amount);
        store.save(player.getUniqueId());
        int after = rankOf(profile);
        if (after > before) {
            Component msg = Text.parse("<#B15CFF>Rank <rank></#B15CFF> <gray>reached! A reward is waiting in /rankup.</gray>",
                    Placeholder.unparsed("rank", Formatting.toRoman(after)));
            player.sendActionBar(msg);
        }
    }

    /** The formula-scaled one-time coin/diamond reward for reaching {@code rank} - placeholder linear values, trivially retunable. */
    public long rewardCoinsForRank(int rank) {
        return REWARD_COINS_PER_RANK * rank;
    }

    public long rewardDiamondsForRank(int rank) {
        return REWARD_DIAMONDS_PER_RANK * rank;
    }

    /** The next rank whose reward hasn't been claimed yet, or -1 if the player is fully caught up - claiming is strictly sequential, same as MilestoneService's ladder. */
    public int nextClaimableRank(PackPlayerProfile profile) {
        int current = rankOf(profile);
        int claimed = profile.getClaimedRank();
        return claimed < current ? claimed + 1 : -1;
    }

    /** Claims exactly the next pending rank reward, if any. Returns false if there's nothing to claim yet. */
    public boolean claimNextReward(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        int next = nextClaimableRank(profile);
        if (next < 0) {
            return false;
        }
        profile.setCoins(profile.getCoins().add(BigInteger.valueOf(rewardCoinsForRank(next))));
        profile.setDiamonds(profile.getDiamonds().add(BigInteger.valueOf(rewardDiamondsForRank(next))));
        profile.setClaimedRank(next);
        store.save(player.getUniqueId());
        return true;
    }

    /** Claims every pending reward at once - same "bulk" convenience the old MAX RANK button used to offer. Returns how many were claimed. */
    public int claimAllRewards(Player player) {
        int count = 0;
        while (claimNextReward(player)) {
            count++;
        }
        return count;
    }
}
