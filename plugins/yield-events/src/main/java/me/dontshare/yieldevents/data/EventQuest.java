package me.dontshare.yieldevents.data;

import java.util.List;

/**
 * One event quest: a thing to do while the event is on, and what it pays.
 * <p>
 * Rewards are deliberately open-ended. {@code commands} runs as console
 * with {@code %player%} substituted, which is how a quest hands out a
 * potion, an exclusive pet, credits or a rank without this plugin needing
 * to know how any of those work - every one of them already has an admin
 * command, and a seasonal event is exactly the sort of thing whose reward
 * table changes every year.
 */
public record EventQuest(String id, String displayName, List<String> description, Goal goal, long target,
                          long rewardCandy, long rewardCoins, long rewardDiamonds, List<String> commands) {

    /** What a quest counts. Each is tracked by the listener that already sees the thing happen. */
    public enum Goal {
        /** Candy earned during this event, all sources. */
        CANDY_EARNED,
        /** Cubes broken inside the event zone. */
        CUBES_BROKEN,
        /** Event eggs hatched. */
        EGGS_HATCHED
    }
}
