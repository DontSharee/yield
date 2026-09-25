package me.dontshare.yieldcore.status;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players who aren't people - the load test's bots. Anything that records
 * player behaviour for the long term (analytics, leaderboards) should leave
 * these out, so a test run never shows up as a spike of real players.
 */
public final class SyntheticPlayers {

    private static final Set<UUID> IDS = ConcurrentHashMap.newKeySet();

    private SyntheticPlayers() {
    }

    public static void add(UUID id) {
        IDS.add(id);
    }

    public static void remove(UUID id) {
        IDS.remove(id);
    }

    public static boolean is(UUID id) {
        return IDS.contains(id);
    }

    public static int count() {
        return IDS.size();
    }
}
