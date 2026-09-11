package me.dontshare.yieldpacks.display;

import me.dontshare.yieldpacks.event.PetLeveledUpEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Bridges yield-packs' own PetLeveledUpEvent into a celebratory particle burst and a live nametag level update - see PetDisplayService#playLevelUpEffect/#refreshLevelLabel. */
public final class PetLevelUpEffectListener implements Listener {

    private final PetDisplayService petDisplayService;

    public PetLevelUpEffectListener(PetDisplayService petDisplayService) {
        this.petDisplayService = petDisplayService;
    }

    @EventHandler
    public void onPetLeveledUp(PetLeveledUpEvent event) {
        petDisplayService.playLevelUpEffect(event.getPlayer(), event.getInstanceId());
        petDisplayService.refreshLevelLabel(event.getPlayer(), event.getInstanceId(), event.getNewLevel());
    }
}
