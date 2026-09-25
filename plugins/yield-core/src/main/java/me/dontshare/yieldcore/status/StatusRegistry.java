package me.dontshare.yieldcore.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Supplier;

/**
 * Sections other plugins add to {@code /yield status} - zones list who is
 * where, the load test its bots, the analytics site its address. Each
 * supplier runs on the main thread when the command does, and returns
 * MiniMessage lines.
 */
public final class StatusRegistry {

    private static final Map<String, Supplier<List<String>>> SECTIONS = new ConcurrentSkipListMap<>();

    private StatusRegistry() {
    }

    public static void register(String title, Supplier<List<String>> lines) {
        SECTIONS.put(title, lines);
    }

    public static void unregister(String title) {
        SECTIONS.remove(title);
    }

    public static Map<String, Supplier<List<String>>> sections() {
        return SECTIONS;
    }
}
