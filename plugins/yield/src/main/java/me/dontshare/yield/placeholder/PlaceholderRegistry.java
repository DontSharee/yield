package me.dontshare.yield.placeholder;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Shared registry other Yield plugins contribute their own placeholders
 * into - mirrors {@code ListenerManager}/{@code CommandManager}'s pattern
 * of core owning shared infrastructure that other plugins register into
 * rather than each plugin building its own.
 * <p>
 * Used directly by {@code ScoreboardManager} (no PlaceholderAPI dependency
 * needed for our own UI to work) and also exposed externally through
 * {@link YieldExpansion} so other plugins (holograms, chat, etc.) can
 * read the same values via {@code %yield_<key>%}.
 */
public final class PlaceholderRegistry {

    private final Map<String, Function<Player, String>> resolvers = new ConcurrentHashMap<>();

    public void register(String key, Function<Player, String> resolver) {
        resolvers.put(key, resolver);
    }

    /** Null if no resolver is registered under this key, or if the resolver itself throws. */
    public String resolve(String key, Player player) {
        Function<Player, String> resolver = resolvers.get(key);
        if (resolver == null) {
            return null;
        }
        try {
            return resolver.apply(player);
        } catch (Exception e) {
            return null;
        }
    }
}
