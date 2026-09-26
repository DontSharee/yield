package me.dontshare.yieldquests.listener;

import me.dontshare.yieldquests.PresentsService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Starts and stops counting a player's playtime toward today's /daily gifts - see PresentsService. */
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
