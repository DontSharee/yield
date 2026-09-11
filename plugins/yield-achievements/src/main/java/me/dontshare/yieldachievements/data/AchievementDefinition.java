package me.dontshare.yieldachievements.data;

import org.bukkit.Material;

import java.math.BigInteger;
import java.util.List;

/**
 * One specific, unique achievement - "goal" lets a trigger need more than
 * one occurrence (e.g. "open 1000 packs"), but most achievements here are
 * meant to be one-off, hand-authored tasks rather than a generic ladder -
 * see {@code MilestoneCategory} for that. Credits are granted automatically
 * the instant an achievement completes - there's no separate claim step
 * for these (unlike milestones).
 */
public record AchievementDefinition(String id, String displayName, List<String> description, Material icon,
                                     GameAction trigger, long goal, BigInteger rewardCredits) {
}
