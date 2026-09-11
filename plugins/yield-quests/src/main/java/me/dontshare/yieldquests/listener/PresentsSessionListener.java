package me.dontshare.yieldquests.listener;

import me.dontshare.yieldquests.PresentsService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Starts/clears a player's /daily session clock - see PresentsService's own class javadoc for why this is deliberately never persisted. */
public final class PresentsSessionListener implements Listener {

    private final PresentsService presentsService;

    public PresentsSessionListener(PresentsService presentsService) {
        this.presentsService = presentsService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        presentsService.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        presentsService.onQuit(event.getPlayer());
    }
}
