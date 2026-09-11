package me.dontshare.yieldteams.listener;

import me.dontshare.yieldteams.TeamService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Self-heals a stale teamId left over from an offline kick/disband - see TeamService#reconcileOnJoin. */
public final class TeamJoinListener implements Listener {

    private final TeamService teamService;

    public TeamJoinListener(TeamService teamService) {
        this.teamService = teamService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        teamService.reconcileOnJoin(event.getPlayer());
    }
}
