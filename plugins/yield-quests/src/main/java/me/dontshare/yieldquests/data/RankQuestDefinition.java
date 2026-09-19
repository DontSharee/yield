package me.dontshare.yieldquests.data;

import org.bukkit.Material;

/**
 * One template in the Rank Quest pool ({@code rank-quests.yml}) - a player
 * always has exactly 3 of these active at once (see {@code RankQuestService}),
 * picked at random without replacement each time the current trio is fully
 * completed. Completing one grants {@code rewardStars} immediately, which
 * feed straight into {@code RankService#grantStars}.
 */
public record RankQuestDefinition(String id, String displayName, Material icon, GameAction action, int goal, long rewardStars) {
}
