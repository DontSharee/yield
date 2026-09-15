package me.dontshare.yieldcore.scoreboard;

import me.clip.placeholderapi.PlaceholderAPI;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player sidebar scoreboard. Each line is backed by its own
 * {@link Team} (entry -> prefix) - that's what actually lets a line hold
 * arbitrary, independently-updatable text in modern Minecraft; a plain
 * Objective score entry can't.
 * <p>
 * Line templates may contain MiniMessage tags (e.g. {@code <#RRGGBB>}),
 * legacy "&amp;" codes (e.g. {@code &7}), and %placeholder% tokens -
 * tokens are resolved via PlaceholderAPI if installed, which covers both
 * Yield's own %yield_x% placeholders
 * (see {@link me.dontshare.yieldcore.placeholder.PlaceholderRegistry}) and
 * any other installed plugin's (e.g. %luckperms_prefix%).
 * <p>
 * {@link #setLines} reuses existing teams across calls rather than
 * tearing everything down every refresh - important since this gets called
 * on a repeating timer to keep stats live.
 */
public final class ScoreboardManager {

    private static final String OBJECTIVE_NAME = "yield";
    private static final int MAX_LINES = 15; // Minecraft's own sidebar display cap

    private final Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();
    /**
     * What each line last actually resolved to, so an unchanged one costs
     * nothing further.
     * <p>
     * This display is rebuilt for every player once a second whether or not
     * anything on it moved. Without this, each of those rebuilds paid a
     * MiniMessage parse per line and - because CraftBukkit broadcasts on
     * every {@code prefix}/{@code setScore} call rather than diffing - sent a
     * packet per line per player per second to say nothing had changed. Most
     * lines on a sidebar are identical second to second, so comparing the
     * resolved string first removes nearly all of both.
     */
    private final Map<UUID, String[]> renderedLines = new ConcurrentHashMap<>();
    private final Map<UUID, String> renderedTitles = new ConcurrentHashMap<>();
    /** Line count at the last render - every score shifts when this changes, and only then. */
    private final Map<UUID, Integer> renderedSizes = new ConcurrentHashMap<>();

    public void setTitle(Player player, String titleTemplate) {
        String resolved = resolve(player, titleTemplate);
        if (resolved.equals(renderedTitles.get(player.getUniqueId()))) {
            return;
        }
        renderedTitles.put(player.getUniqueId(), resolved);
        getObjective(player).displayName(Text.parse(resolved));
    }

    /** Replaces the visible line list, top to bottom, reusing existing per-line teams where possible. */
    public void setLines(Player player, List<String> lineTemplates) {
        Scoreboard board = boardFor(player);
        Objective objective = getObjective(player);
        UUID playerId = player.getUniqueId();
        int size = lineTemplates.size();

        String[] previous = renderedLines.get(playerId);
        if (previous == null || previous.length != size) {
            previous = new String[size];
            renderedLines.put(playerId, previous);
        }
        boolean sizeChanged = !Integer.valueOf(size).equals(renderedSizes.get(playerId));

        for (int i = 0; i < size; i++) {
            String entry = entryFor(i);
            Team team = board.getTeam(teamNameFor(i));
            if (team == null) {
                team = board.registerNewTeam(teamNameFor(i));
                team.addEntry(entry);
            }

            String resolved = resolve(player, lineTemplates.get(i));
            if (!resolved.equals(previous[i])) {
                previous[i] = resolved;
                team.prefix(Text.parse(resolved));
            }
            if (sizeChanged) {
                objective.getScore(entry).setScore(size - i);
            }
        }
        renderedSizes.put(playerId, size);

        // Clean up any leftover lines from a previous, longer render.
        for (int i = size; i < MAX_LINES; i++) {
            Team team = board.getTeam(teamNameFor(i));
            if (team == null) {
                break;
            }
            team.unregister();
            board.resetScores(entryFor(i));
        }
    }

    /** Resets the player's live scoreboard. Call this while they're still online. */
    public void clear(Player player) {
        forget(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    /** Drops the cached board reference without touching the (already-disconnected) player. Call on quit. */
    public void unload(UUID playerId) {
        forget(playerId);
    }

    private void forget(UUID playerId) {
        boards.remove(playerId);
        renderedLines.remove(playerId);
        renderedTitles.remove(playerId);
        renderedSizes.remove(playerId);
    }

    private String resolve(Player player, String template) {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                ? PlaceholderAPI.setPlaceholders(player, template)
                : template;
    }

    /**
     * A scoreboard "entry" is always rendered as visible text after the
     * Team prefix, in every Minecraft version - readable strings like
     * "line_0" would literally show up on screen. Legacy color codes
     * render as zero-width, so using one as the entry keeps only the
     * prefix (the actual line content) visible.
     */
    private String entryFor(int index) {
        return ChatColor.values()[index].toString();
    }

    private String teamNameFor(int index) {
        return "yl_line_" + index;
    }

    /**
     * The Scoreboard object this specific player is actually bound to right
     * now (creating it on first call, same as every other line here) -
     * every player gets their own private board for the sidebar (see class
     * Javadoc), so a real, everyone-visible {@link Team} (e.g. a nameplate
     * cosmetic) has to be registered on THIS object for each viewer, not on
     * {@code Bukkit.getScoreboardManager().getMainScoreboard()} - a client
     * only ever renders the one scoreboard it's currently assigned, and
     * {@link Player#setScoreboard} above means that's never the main one.
     */
    public Scoreboard scoreboardFor(Player player) {
        return boardFor(player);
    }

    private Scoreboard boardFor(Player player) {
        return boards.computeIfAbsent(player.getUniqueId(), id -> {
            Scoreboard newBoard = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(newBoard);
            return newBoard;
        });
    }

    private Objective getObjective(Player player) {
        Scoreboard board = boardFor(player);
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, Component.text("Yield"));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        return objective;
    }
}
