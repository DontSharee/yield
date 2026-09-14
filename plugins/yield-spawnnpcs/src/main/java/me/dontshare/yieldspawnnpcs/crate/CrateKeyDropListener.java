package me.dontshare.yieldspawnnpcs.crate;

import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Every ore cube kill gets one independent roll per crate tier at its own Key - see {@link CrateService#rollKeyDrops}. */
public final class CrateKeyDropListener implements Listener {

    private final CrateService crateService;

    public CrateKeyDropListener(CrateService crateService) {
        this.crateService = crateService;
    }

    @EventHandler
    public void onOreCubeKilled(OreCubeKilledEvent event) {
        crateService.rollKeyDrops(event.getPlayer());
    }
}
