package me.dontshare.yieldpacks.display;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Builds a joining player's pet instances immediately and tears them down on quit so nothing lingers client-side. */
public final class PetDisplayListener implements Listener {

    private final PetDisplayService displayService;

    public PetDisplayListener(PetDisplayService displayService) {
        this.displayService = displayService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        displayService.refresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        displayService.despawnAll(event.getPlayer());
    }
}
