package me.dontshare.yieldpacks.pity;

import me.dontshare.yieldpacks.data.PackContentLoader;

import java.util.List;
import java.util.function.Supplier;

/**
 * Pity is layered on top of {@code LuckService} rather than replacing it -
 * every {@code tier.rolls()}th pack open, the very next roll (and only that
 * one) gets {@code tier.multiplier()} luck, then falls back to normal. When
 * a roll count is a multiple of several configured tiers at once (e.g. 100
 * is a multiple of both a 10-roll and a 100-roll tier), the highest
 * multiplier wins - so escalating "every 10 -> 2x, every 100 -> 3x" tiers
 * behave the way the name implies without any special-casing here.
 */
public final class PityService {

    private static final int BAR_LENGTH = 10;

    private final Supplier<PackContentLoader.ContentSnapshot> content;

    public PityService(Supplier<PackContentLoader.ContentSnapshot> content) {
        this.content = content;
    }

    /** The luck multiplier the next roll should get, given how many rolls this player has made so far. */
    public double multiplierFor(long rollCountBeforeThisRoll) {
        if (rollCountBeforeThisRoll <= 0) {
            return 1.0;
        }
        double best = 1.0;
        for (PityTier tier : content.get().pityTiers()) {
            if (rollCountBeforeThisRoll % tier.rolls() == 0) {
                best = Math.max(best, tier.multiplier());
            }
        }
        return best;
    }

    /** Progress toward the next milestone (sized by the smallest configured tier) - null if no tiers are configured. */
    public PityProgress progress(long rollCount) {
        List<PityTier> tiers = content.get().pityTiers();
        if (tiers.isEmpty()) {
            return null;
        }
        int smallest = tiers.get(0).rolls();
        int current = (int) (rollCount % smallest);
        long nextMilestone = rollCount - current + smallest;
        double nextMultiplier = multiplierAtMilestone(nextMilestone, tiers);
        return new PityProgress(current, smallest, nextMultiplier);
    }

    /** A 10-square {@code ■}/{@code □} MiniMessage progress bar toward the next pity milestone - the single source of truth for pity-bar rendering (see PackActionBarService and PackRevealAnimationService, both of which show this). Empty string if no tiers are configured. */
    public String renderBar(long rollCount) {
        PityProgress progress = progress(rollCount);
        if (progress == null) {
            return "";
        }
        int filled = Math.round((float) progress.current() / progress.max() * BAR_LENGTH);
        StringBuilder builder = new StringBuilder("<#4BD9FF>");
        for (int i = 0; i < BAR_LENGTH; i++) {
            builder.append(i < filled ? "■" : "<dark_gray>□<#4BD9FF>");
        }
        return builder.toString();
    }

    /** "(<current>/<max>, next x<mult>)" - the exact suffix PackActionBarService's normal display shows, reused verbatim by PackRevealAnimationService's own action-bar line so both read identically apart from what's shown to their left. Empty string if no tiers are configured. */
    public String renderProgressSuffix(long rollCount) {
        PityProgress progress = progress(rollCount);
        if (progress == null) {
            return "";
        }
        String mult = progress.nextMultiplier() % 1 == 0
                ? String.valueOf((int) progress.nextMultiplier())
                : String.valueOf(progress.nextMultiplier());
        return "(" + progress.current() + "/" + progress.max() + ", next x" + mult + ")";
    }

    private double multiplierAtMilestone(long milestone, List<PityTier> tiers) {
        double best = 1.0;
        for (PityTier tier : tiers) {
            if (milestone % tier.rolls() == 0) {
                best = Math.max(best, tier.multiplier());
            }
        }
        return best;
    }
}
