package me.dontshare.yieldquests.data;

/**
 * One difficulty tier's actual quest content - e.g. category "combat"'s HARD
 * entry. {@code rewardCredits} and {@code rewardPetId} are both optional
 * (0 / null) - most tiers are still just coins/diamonds, but a tier can layer on
 * a bit of the auction house's own credits currency and/or a single
 * guaranteed pet on top, for variety beyond "another pile of coins."
 */
public record QuestDefinition(String displayName, GameAction action, int goal,
                               long rewardCoins, long rewardDiamonds, long rewardCredits, String rewardPetId) {
}
