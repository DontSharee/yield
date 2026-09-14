package me.dontshare.yieldachievements.data;

import java.math.BigInteger;

/**
 * One rung of a {@link MilestoneCategory}'s ladder - reaching "goal" (against
 * that category's own shared counter) unlocks this tier's reward, claimed
 * independently of every other tier. {@code rewardPotionId} is optional (null
 * for none) - any valid potion id (e.g. "POTION_LUCK_2_600") works, handed to
 * the player as a real item on claim rather than auto-consumed.
 */
public record MilestoneTier(long goal, BigInteger rewardCoins, BigInteger rewardDiamonds, BigInteger rewardCredits, String rewardPotionId) {
}
