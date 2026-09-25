package me.dontshare.yieldcore.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Player data that failed to reach the database - the one kind of data
 * problem that loses progress. Every failed save or unserializable record is
 * noted here as well as logged, so it shows up in {@code /yield status}
 * and the alerts instead of only scrolling past in the console.
 */
public final class DataHealth {

    /** One failed write: which plugin's data, whose, why, and whether it was during shutdown. */
    public record Failure(long at, String store, UUID player, String reason) {
    }

    private static final Deque<Failure> RECENT = new ArrayDeque<>();
    private static long total;

    private DataHealth() {
    }

    public static void saveFailed(String store, UUID player, Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String reason = root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
        synchronized (RECENT) {
            total++;
            RECENT.addLast(new Failure(System.currentTimeMillis(), store, player, reason));
            while (RECENT.size() > 50) {
                RECENT.removeFirst();
            }
        }
    }

    public static long total() {
        synchronized (RECENT) {
            return total;
        }
    }

    /** Newest first. */
    public static List<Failure> since(long sinceMillis) {
        List<Failure> list = new ArrayList<>();
        synchronized (RECENT) {
            for (Failure failure : RECENT) {
                if (failure.at() >= sinceMillis) {
                    list.add(failure);
                }
            }
        }
        Collections.reverse(list);
        return list;
    }
}
