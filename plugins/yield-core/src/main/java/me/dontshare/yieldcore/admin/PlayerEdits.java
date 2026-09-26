package me.dontshare.yieldcore.admin;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every player stat an admin tool may change - registered by the plugin that
 * owns it, so the tool (the analytics site's editor) never needs to know
 * each plugin's record classes.
 * <p>
 * A stat reads and writes one plugin's record through that plugin's own
 * setters, and {@link PlayerDataStore#edit} decides where: the cached record
 * of a player who's online, or the stored one of a player who isn't.
 */
public final class PlayerEdits {

    /** How a stat's value is entered. */
    public enum Kind {
        /** A whole number of any size, shorthand like {@code 1.5m} accepted - currencies. */
        AMOUNT,
        /** A plain whole number within {@link Stat#min()}..{@link Stat#max()}. */
        NUMBER,
        /** One of {@link Stat#options()}. */
        CHOICE,
        /** Something done rather than set (give a pet); the value picks what, from {@link Stat#options()} or free text. */
        ACTION
    }

    /** A value a CHOICE or ACTION stat accepts, and what to show for it. */
    public record Option(String value, String label, String group) {
    }

    /** How to reverse an edit: set {@code stat} to {@code value}. Null when it can't be reversed. */
    public record Undo(String stat, String value) {
    }

    /** One applied edit. */
    public record Result(String stat, String before, String after, Undo undo, PlayerDataStore.EditTarget where) {
    }

    /**
     * One editable stat. {@code read} gives the current value in the form
     * {@code write} accepts; {@code write} applies a new one (throwing
     * {@link IllegalArgumentException} to refuse it) and says how to undo
     * it. {@code afterLive} runs on the main thread after an edit to an
     * online player, for anything that has to be redrawn.
     */
    public record Stat<T extends PlayerRecord>(
            String id, String label, String group, Kind kind, long min, long max, String hint,
            PlayerDataStore<T> store, Function<T, String> read, BiFunction<T, String, Undo> write,
            Supplier<List<Option>> options, Consumer<Player> afterLive, Plugin owner) {
    }

    /** Something to show about a player that isn't one value - their pet collection, say. {@code read} returns plain maps and lists. */
    public record View<T extends PlayerRecord>(String id, PlayerDataStore<T> store, Function<T, Object> read, Plugin owner) {
    }

    private static final List<Stat<?>> STATS = new CopyOnWriteArrayList<>();
    private static final List<View<?>> VIEWS = new CopyOnWriteArrayList<>();

    public static <T extends PlayerRecord> void registerView(View<T> view) {
        VIEWS.removeIf(existing -> existing.id().equals(view.id()));
        VIEWS.add(view);
    }

    /** One player's view {@code id}, read wherever their record lives; null if there's no such view or player. */
    @SuppressWarnings("unchecked")
    public static <T extends PlayerRecord> CompletableFuture<Object> view(UUID playerId, String id) {
        for (View<?> raw : VIEWS) {
            if (raw.id().equals(id)) {
                View<T> view = (View<T>) raw;
                return view.store().read(playerId, view.read());
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private PlayerEdits() {
    }

    public static <T extends PlayerRecord> void register(Stat<T> stat) {
        STATS.removeIf(existing -> existing.id().equals(stat.id()));
        STATS.add(stat);
    }

    /** Call from onDisable. */
    public static void unregisterAll(Plugin owner) {
        STATS.removeIf(stat -> stat.owner() == owner);
        VIEWS.removeIf(view -> view.owner() == owner);
    }

    public static List<Stat<?>> stats() {
        return List.copyOf(STATS);
    }

    public static Stat<?> find(String id) {
        for (Stat<?> stat : STATS) {
            if (stat.id().equals(id)) {
                return stat;
            }
        }
        return null;
    }

    /** Every stat's current value for one player, by stat id - null values for stats with nothing stored. */
    public static CompletableFuture<Map<String, String>> read(UUID playerId) {
        Map<PlayerDataStore<?>, List<Stat<?>>> byStore = new LinkedHashMap<>();
        for (Stat<?> stat : STATS) {
            byStore.computeIfAbsent(stat.store(), store -> new ArrayList<>()).add(stat);
        }
        List<CompletableFuture<Map<String, String>>> parts = new ArrayList<>();
        for (Map.Entry<PlayerDataStore<?>, List<Stat<?>>> entry : byStore.entrySet()) {
            parts.add(readFrom(entry.getKey(), entry.getValue(), playerId));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (CompletableFuture<Map<String, String>> part : parts) {
                Map<String, String> map = part.join();
                if (map != null) {
                    values.putAll(map);
                }
            }
            return values;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends PlayerRecord> CompletableFuture<Map<String, String>> readFrom(
            PlayerDataStore<?> rawStore, List<Stat<?>> stats, UUID playerId) {
        PlayerDataStore<T> store = (PlayerDataStore<T>) rawStore;
        return store.read(playerId, record -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (Stat<?> raw : stats) {
                Stat<T> stat = (Stat<T>) raw;
                values.put(stat.id(), stat.read().apply(record));
            }
            return values;
        });
    }

    /**
     * Sets one stat, wherever the player's record lives. Fails with
     * {@link IllegalArgumentException} for a refused value and
     * {@link NoSuchElementException} for an unknown stat or player.
     */
    @SuppressWarnings("unchecked")
    public static <T extends PlayerRecord> CompletableFuture<Result> apply(UUID playerId, String statId, String value) {
        Stat<T> stat = (Stat<T>) find(statId);
        if (stat == null) {
            return CompletableFuture.failedFuture(new NoSuchElementException("No such stat: " + statId));
        }
        String[] before = new String[1];
        String[] after = new String[1];
        Undo[] undo = new Undo[1];
        return stat.store().edit(playerId, record -> {
            before[0] = stat.read().apply(record);
            undo[0] = stat.write().apply(record, value == null ? "" : value.trim());
            after[0] = stat.read().apply(record);
        }).thenApply(where -> {
            if (where == PlayerDataStore.EditTarget.LIVE && stat.afterLive() != null) {
                Plugin plugin = stat.owner();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) {
                        stat.afterLive().accept(player);
                    }
                });
            }
            return new Result(stat.id(), before[0], after[0], undo[0], where);
        });
    }

    // ------------------------------------------------------------------ helpers for registering

    /** A plain setter's undo: back to what it was. */
    public static Undo restore(String statId, String before) {
        return new Undo(statId, before);
    }

    /** Parses a whole-number amount ("250", "1.5m", "2e9"), refusing negatives. */
    public static java.math.BigInteger amount(String raw) {
        java.math.BigInteger value;
        try {
            value = me.dontshare.yieldcore.text.Formatting.unformatToBigInteger(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not an amount: '" + raw + "'");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Can't be negative.");
        }
        return value;
    }

    /** Parses a whole number within {@code min..max}. */
    public static long number(String raw, long min, long max) {
        long value;
        try {
            value = amount(raw).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Too big.");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("Must be between " + min + " and " + max + ".");
        }
        return value;
    }
}
