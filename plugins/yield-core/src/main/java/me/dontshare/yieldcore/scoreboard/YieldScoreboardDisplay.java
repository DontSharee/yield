package me.dontshare.yieldcore.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Defines the actual scoreboard layout/content and keeps it refreshed.
 * {@link ScoreboardManager} is the generic per-line-text plumbing; this is
 * the "customize me" part - swap the base template in {@link #render} for
 * whatever this project actually wants to show, using placeholders
 * registered against {@code PlaceholderRegistry}/PlaceholderAPI.
 * <p>
 * Other plugins that want to contribute their own lines (a currency
 * balance, an income rate, etc.) without owning the whole scoreboard should
 * call {@link #addLineProvider} rather than reaching into
 * {@link ScoreboardManager} directly - the display loop runs once per
 * second regardless, so two plugins independently calling
 * {@code ScoreboardManager#setLines} would just overwrite each other.
 */
public final class YieldScoreboardDisplay implements Listener {

    private static final long REFRESH_INTERVAL_TICKS = 20L; // 1 second

    private final JavaPlugin plugin;
    private final ScoreboardManager scoreboardManager;
    private final List<Function<Player, List<String>>> extraLineProviders = new CopyOnWriteArrayList<>();
    private final List<BiFunction<Player, List<String>, List<String>>> lineTransformers = new CopyOnWriteArrayList<>();
    // Lets exactly one feature (the tutorial checklist, today) take over a
    // player's WHOLE sidebar instead of just contributing a line - a
    // scoreboard only has one sidebar slot, so "swap the whole thing" and
    // "append a line" can't both be keyed-provider patterns at once here.
    // Returning null means "no override, render normally".
    private volatile Function<Player, ScoreboardContent> overrideProvider = player -> null;

    public YieldScoreboardDisplay(JavaPlugin plugin, ScoreboardManager scoreboardManager) {
        this.plugin = plugin;
        this.scoreboardManager = scoreboardManager;
    }

    /** Appends this provider's lines (in registration order) below the base template on every refresh. */
    public void addLineProvider(Function<Player, List<String>> provider) {
        extraLineProviders.add(provider);
    }

    /**
     * Rewrites the assembled lines before they are drawn - for a feature
     * that needs to CHANGE what another plugin already put there rather
     * than add to it.
     * <p>
     * A seasonal event swapping the wallet's Credits line for its own Candy
     * while a player stands in the event zone is the case this exists for.
     * Appending a line instead would grow the sidebar for two weeks and
     * leave a number on screen that is irrelevant exactly where the event
     * is relevant. Transformers run in registration order, after every
     * provider, and are handed a mutable copy.
     */
    public void addLineTransformer(BiFunction<Player, List<String>, List<String>> transformer) {
        lineTransformers.add(transformer);
    }

    /** A whole title+lines sidebar to substitute in place of the normal template, e.g. the tutorial checklist. */
    public record ScoreboardContent(String title, List<String> lines) {
    }

    /** Replaces the normal sidebar with whatever {@code provider} returns, for any player it returns non-null for. Only one such override can be registered at a time. */
    public void setOverrideProvider(Function<Player, ScoreboardContent> provider) {
        this.overrideProvider = provider;
    }

    /** Starts the periodic refresh loop. Call once at plugin startup. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                render(player);
            }
        }, 0L, REFRESH_INTERVAL_TICKS);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        render(event.getPlayer());
    }

    /** Forces an immediate full re-render for one player, instead of waiting for the next periodic tick - call this right after changing a stat a line provider reads, so it updates the moment it happens rather than up to a second later. */
    public void refresh(Player player) {
        render(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scoreboardManager.unload(event.getPlayer().getUniqueId());
    }

    private void render(Player player) {
        ScoreboardContent override = overrideProvider.apply(player);
        if (override != null) {
            scoreboardManager.setTitle(player, override.title());
            scoreboardManager.setLines(player, override.lines());
            return;
        }

        scoreboardManager.setTitle(player, "<#8CD5EC>&lY<#97DAF2>&lI<#A2E0F9>&lE<#ADE5FF>&lL<#8CD5EC>&lD");
        List<String> lines = new ArrayList<>();
        lines.add("&8Season I");
        for (Function<Player, List<String>> provider : extraLineProviders) {
            lines.addAll(provider.apply(player));
        }
        for (BiFunction<Player, List<String>, List<String>> transformer : lineTransformers) {
            lines = new ArrayList<>(transformer.apply(player, lines));
        }
        scoreboardManager.setLines(player, lines);
    }
}
