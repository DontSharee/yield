package me.dontshare.yieldpacks.starter;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A short delay before opening the GUI - opening an inventory in the exact
 * same tick as {@link PlayerJoinEvent} is a well-known case where the
 * client isn't reliably ready yet (the screen can silently fail to open,
 * or close itself immediately) - one second is comfortably past that and
 * gives the player a moment to actually load in first.
 */
public final class StarterPetJoinListener implements Listener {

    private static final long OPEN_DELAY_TICKS = 20L;

    private final StarterPetService service;
    private final StarterPetGui gui;
    private final JavaPlugin plugin;

    public StarterPetJoinListener(StarterPetService service, StarterPetGui gui, JavaPlugin plugin) {
        this.service = service;
        this.gui = gui;
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!service.needsStarterPet(player)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && service.needsStarterPet(player)) {
                gui.open(player);
            }
        }, OPEN_DELAY_TICKS);
    }
}
