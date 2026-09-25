package me.dontshare.yieldanalytics.collect;

import me.dontshare.yieldcore.status.SyntheticPlayers;

import java.util.UUID;

/**
 * Who the analytics leave out: the load test's bots, unless analytics.yml's
 * {@code count-load-test-bots} says to count them (handy for seeing the
 * dashboard full during a test, and never for a live server).
 */
public final class Exclusions {

    private static final String BOT_PREFIX = "LT_Bot";
    private static volatile boolean countBots;

    private Exclusions() {
    }

    public static void setCountBots(boolean count) {
        countBots = count;
    }

    public static boolean excluded(UUID id) {
        return !countBots && SyntheticPlayers.is(id);
    }

    public static boolean excludedName(String name) {
        return !countBots && name != null && name.startsWith(BOT_PREFIX);
    }
}
