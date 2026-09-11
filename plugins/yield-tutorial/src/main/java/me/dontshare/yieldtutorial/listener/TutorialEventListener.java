package me.dontshare.yieldtutorial.listener;

import me.dontshare.yieldpacks.event.PackOpenedEvent;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldtutorial.TutorialService;
import me.dontshare.yieldtutorial.data.CompletionTrigger;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import me.dontshare.yieldzones.event.ZoneEnteredEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Bridges every relevant cross-plugin event into {@link TutorialService#onTrigger}. */
public final class TutorialEventListener implements Listener {

    private final TutorialService tutorialService;

    public TutorialEventListener(TutorialService tutorialService) {
        this.tutorialService = tutorialService;
    }

    @EventHandler
    public void onPackOpened(PackOpenedEvent event) {
        tutorialService.onTrigger(event.getPlayer(), CompletionTrigger.OPEN_PACK);
    }

    @EventHandler
    public void onPetEquipped(PetEquippedEvent event) {
        tutorialService.onTrigger(event.getPlayer(), CompletionTrigger.EQUIP_PET);
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        tutorialService.onTrigger(event.getPlayer(), CompletionTrigger.KILL_CUBE);
    }

    @EventHandler
    public void onZoneEntered(ZoneEnteredEvent event) {
        tutorialService.onTrigger(event.getPlayer(), CompletionTrigger.ENTER_ZONE);
    }
}
