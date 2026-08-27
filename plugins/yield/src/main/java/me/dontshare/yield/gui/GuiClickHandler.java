package me.dontshare.yield.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

/** A single slot's click behavior in a {@link Gui}. */
@FunctionalInterface
public interface GuiClickHandler {

    void onClick(Player player, InventoryClickEvent event);
}
