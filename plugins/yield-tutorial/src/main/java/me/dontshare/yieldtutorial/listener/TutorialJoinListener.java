package me.dontshare.yieldtutorial.listener;

import me.dontshare.yieldtutorial.TutorialService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** On join: re-shows the player's current tutorial step (if any) and lets the guide NPC's own visibility tick pick them back up. */
public final class TutorialJoinListener implements Listener {

    private final TutorialService tutorialService;

    public TutorialJoinListener(TutorialService tutorialService) {
        this.tutorialService = tutorialService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        tutorialService.onJoin(event.getPlayer());
    }
}
