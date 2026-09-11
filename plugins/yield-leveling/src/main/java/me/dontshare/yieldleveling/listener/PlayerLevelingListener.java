package me.dontshare.yieldleveling.listener;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldleveling.PlayerLevelingService;
import me.dontshare.yieldmining.event.OreMinedEvent;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Bridges yield-zones'/yield-mining's own kill/mine events into player XP, and re-syncs the vanilla bar on join - neither module needs any awareness of this one. */
public final class PlayerLevelingListener implements Listener {

    private final PlayerLevelingService service;
    private final PlayerDataStore<PackPlayerProfile> store;

    public PlayerLevelingListener(PlayerLevelingService service, PlayerDataStore<PackPlayerProfile> store) {
        this.service = service;
        this.store = store;
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        service.grantXp(event.getPlayer(), event.getTier().xpValue());
    }

    @EventHandler
    public void onOreMined(OreMinedEvent event) {
        service.grantXp(event.getPlayer(), event.getXp());
    }

    /** The profile's own load (see PackPlayerProfileManager) never touches the vanilla XP bar itself - without this, a freshly-joined player's bar would stay at whatever it happened to be (usually empty) until their next cube kill. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PackPlayerProfile profile = store.getCached(event.getPlayer().getUniqueId());
        if (profile != null) {
            service.syncBar(event.getPlayer(), profile);
        }
    }
}
