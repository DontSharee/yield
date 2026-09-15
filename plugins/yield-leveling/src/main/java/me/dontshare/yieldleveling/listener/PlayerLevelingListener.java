package me.dontshare.yieldleveling.listener;

import me.dontshare.yieldleveling.PlayerLevelingService;
import me.dontshare.yieldmining.event.OreMinedEvent;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Bridges yield-zones'/yield-mining's own kill/mine events into player XP, and re-syncs the vanilla bar on join - neither module needs any awareness of this one. */
public final class PlayerLevelingListener implements Listener {

    private final PlayerLevelingService service;

    public PlayerLevelingListener(PlayerLevelingService service) {
        this.service = service;
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        service.grantXp(event.getPlayer(), event.getTier().xpValue());
    }

    @EventHandler
    public void onOreMined(OreMinedEvent event) {
        service.grantXp(event.getPlayer(), event.getXp());
    }

    /** The load itself never touches the vanilla XP bar - without this, a freshly-joined player's bar would stay at whatever it happened to be (usually empty) until their next cube kill. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.syncBar(event.getPlayer());
    }
}
