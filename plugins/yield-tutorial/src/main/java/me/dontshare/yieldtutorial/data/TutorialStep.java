package me.dontshare.yieldtutorial.data;

import java.util.List;

/**
 * {@code label} is the short line shown per-step on the sidebar checklist
 * (e.g. "Open a pack") - separate from {@code dialogue}, the NPC's longer
 * chat lines. {@code goal} lets a step require more than one occurrence of
 * its trigger (e.g. "Kill 5 cubes") - the checklist shows live "X/goal"
 * progress toward it; almost every step just uses the default of 1.
 */
public record TutorialStep(String id, String label, List<String> dialogue, CompletionTrigger completesOn,
                            int goal, long rewardCoins, int rewardGems) {
}
