package me.dontshare.yield.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * The single listener for every open {@link Gui} - recovers which one from
 * {@code event.getInventory().getHolder()} (Gui is its own holder) rather
 * than needing a per-GUI listener registration. Cancels every click/drag
 * against a Gui's top inventory by default (nothing is takeable/movable
 * except through a slot's own {@link GuiClickHandler}), and only dispatches
 * clicks that land on the Gui itself, not the player's own inventory below it.
 */
public final class GuiListener implements Listener {

    private final GuiManager manager;

    public GuiListener(GuiManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        gui.handleClick(player, event);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Gui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getView().getTopInventory().getHolder() instanceof Gui) {
            manager.forget(player.getUniqueId());
        }
    }
}
