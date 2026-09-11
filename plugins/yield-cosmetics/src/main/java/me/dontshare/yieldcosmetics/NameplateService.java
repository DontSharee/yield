package me.dontshare.yieldcosmetics;

import me.dontshare.yieldcore.scoreboard.ScoreboardManager;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies the equipped Nameplate cosmetic via a real {@link Team} - the
 * standard Bukkit technique for restyling a player's floating nametag and
 * tab-list entry. There's a real wrinkle here that isn't in the plan this
 * was originally scoped from: {@code Bukkit.getScoreboardManager().getMainScoreboard()}
 * is NOT what any player actually sees, because {@link ScoreboardManager}
 * (yield-core's per-player sidebar system) already calls
 * {@link Player#setScoreboard} to give every player their own private board
 * for the sidebar - a client only ever renders whichever single Scoreboard
 * object it's currently assigned, so a team registered on the main one is
 * invisible to everyone. The fix is to register the SAME team, kept in
 * sync, on every online player's own private board instead (see
 * {@link ScoreboardManager#scoreboardFor}) - one team per unique equipped
 * style, replicated across every viewer's board whenever a wearer's
 * nameplate changes, and replayed onto a newly-joining viewer's own
 * (brand new) board via {@link #catchUp} so they immediately see everyone
 * already wearing one.
 */
public final class NameplateService {

    private static final String TEAM_PREFIX = "cos_";

    private final ScoreboardManager scoreboardManager;
    // Every online player's currently active nameplate style, keyed by
    // wearer - lets a freshly-joined viewer's brand-new board get caught up
    // with everyone else already wearing one (see catchUp).
    private final Map<UUID, String> activeStyleByWearer = new ConcurrentHashMap<>();

    public NameplateService(ScoreboardManager scoreboardManager) {
        this.scoreboardManager = scoreboardManager;
    }

    /** Call whenever {@code wearer}'s equipped nameplate changes - updates every online player's own board so everyone sees it. */
    public void apply(Player wearer, String style) {
        if (style == null) {
            activeStyleByWearer.remove(wearer.getUniqueId());
        } else {
            activeStyleByWearer.put(wearer.getUniqueId(), style);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyOnBoard(scoreboardManager.scoreboardFor(viewer), wearer.getName(), style);
        }
    }

    /** Call when {@code viewer} joins - replays every currently-known nameplate assignment onto their own brand-new board. */
    public void catchUp(Player viewer) {
        Scoreboard board = scoreboardManager.scoreboardFor(viewer);
        for (Map.Entry<UUID, String> entry : activeStyleByWearer.entrySet()) {
            Player wearer = Bukkit.getPlayer(entry.getKey());
            if (wearer != null) {
                applyOnBoard(board, wearer.getName(), entry.getValue());
            }
        }
    }

    private void applyOnBoard(Scoreboard board, String entryName, String style) {
        Team existing = board.getEntryTeam(entryName);
        if (existing != null && existing.getName().startsWith(TEAM_PREFIX)) {
            existing.removeEntry(entryName);
        }
        if (style == null) {
            return;
        }
        String teamName = TEAM_PREFIX + Integer.toHexString(style.hashCode());
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.prefix(Text.parse(style));
        team.addEntry(entryName);
    }
}
