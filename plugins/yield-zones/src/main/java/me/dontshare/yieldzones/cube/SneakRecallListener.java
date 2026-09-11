package me.dontshare.yieldzones.cube;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/** Sneaking clears every one of the player's pet target assignments (shared or per-slot) and sends them all back to formation. */
public final class SneakRecallListener implements Listener {

    private final PetCombatController combatController;

    public SneakRecallListener(PetCombatController combatController) {
        this.combatController = combatController;
    }

    @EventHandler
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            combatController.recallAll(event.getPlayer());
        }
    }
}
