package me.dontshare.yieldtutorial.listener;

import me.dontshare.yieldtutorial.TutorialService;
import me.dontshare.yieldtutorial.npc.TutorialNpcManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** On join: re-shows the player's current tutorial step (if any) and lets the guide NPC's own visibility tick pick them back up. */
public final class TutorialJoinListener implements Listener {

    private final TutorialService tutorialService;
    private final TutorialNpcManager npcManager;

    public TutorialJoinListener(TutorialService tutorialService, TutorialNpcManager npcManager) {
        this.tutorialService = tutorialService;
        this.npcManager = npcManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        tutorialService.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        npcManager.forget(event.getPlayer().getUniqueId());
    }
}
