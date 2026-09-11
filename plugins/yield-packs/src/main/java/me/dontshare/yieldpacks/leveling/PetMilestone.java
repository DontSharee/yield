package me.dontshare.yieldpacks.leveling;

/** One configured milestone - {@code value} is only meaningful for effects that need a magnitude (e.g. EARNINGS_BONUS's percentage); unused effects just leave it 0. */
public record PetMilestone(int level, MilestoneEffect effect, double value) {
}
