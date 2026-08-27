package me.dontshare.yieldcore.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Defines the actual scoreboard layout/content and keeps it refreshed.
 * {@link ScoreboardManager} is the generic per-line-text plumbing; this is
 * the "customize me" part - swap the templates in {@link #render} for
 * whatever this project actually wants to show, using placeholders
 * registered against {@code PlaceholderRegistry}/PlaceholderAPI.
 */
public final class YieldScoreboardDisplay implements Listener {

    private static final long REFRESH_INTERVAL_TICKS = 20L; // 1 second

    private final JavaPlugin plugin;
    private final ScoreboardManager scoreboardManager;

    public YieldScoreboardDisplay(JavaPlugin plugin, ScoreboardManager scoreboardManager) {
        this.plugin = plugin;
        this.scoreboardManager = scoreboardManager;
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
        scoreboardManager.setLines(player, List.of(
                " ",
                "&f%yield_username%",
                " "
        ));
    }
}
