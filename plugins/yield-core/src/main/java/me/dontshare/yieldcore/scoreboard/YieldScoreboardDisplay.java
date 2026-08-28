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

    public YieldScoreboardDisplay(JavaPlugin plugin, ScoreboardManager scoreboardManager) {
        this.plugin = plugin;
        this.scoreboardManager = scoreboardManager;
    }

    /** Appends this provider's lines (in registration order) below the base template on every refresh. */
    public void addLineProvider(Function<Player, List<String>> provider) {
        extraLineProviders.add(provider);
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scoreboardManager.unload(event.getPlayer().getUniqueId());
    }

    private void render(Player player) {
        scoreboardManager.setTitle(player, "<#74C7FF><bold>Yield</bold>");

        List<String> lines = new ArrayList<>();
        lines.add(" ");
        lines.add("&f%yield_username%");
        for (Function<Player, List<String>> provider : extraLineProviders) {
            lines.addAll(provider.apply(player));
        }
        lines.add(" ");

        scoreboardManager.setLines(player, lines);
    }
}
