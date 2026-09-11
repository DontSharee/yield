package me.dontshare.yieldcosmetics.listener;

import me.dontshare.yieldcosmetics.CosmeticService;
import me.dontshare.yieldcosmetics.NameplateService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * On join: pushes the joining player's own equipped nameplate out to
 * everyone else's board ({@code applyEquippedNameplate}), and separately
 * catches their own brand-new board up on everyone else's already-equipped
 * nameplates ({@code catchUp}) - see NameplateService's own Javadoc for why
 * both directions are needed.
 */
public final class NameplateJoinListener implements Listener {

    private final CosmeticService cosmeticService;
    private final NameplateService nameplateService;

    public NameplateJoinListener(CosmeticService cosmeticService, NameplateService nameplateService) {
        this.cosmeticService = cosmeticService;
        this.nameplateService = nameplateService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        nameplateService.catchUp(event.getPlayer());
        cosmeticService.applyEquippedNameplate(event.getPlayer());
    }
}
