package me.dontshare.yieldcore.gui;

import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * A fully server-controlled chest-inventory menu: every interactive slot is
 * declared up front with its item and click behavior via {@link #builder},
 * every other slot/click is inert (nothing can be taken, placed, or shift-
 * clicked out - see {@link GuiListener}, which cancels everything except
 * dispatching to a slot's own {@link GuiClickHandler}).
 * <p>
 * Is its own {@link InventoryHolder} - {@link GuiListener} recovers the
 * {@link Gui} straight from {@code event.getInventory().getHolder()}, no
 * separate per-player bookkeeping needed just to route a click. {@link
 * GuiManager} still tracks current/previous GUI per player, but only for
 * the "go back" convenience ({@link GuiManager#openLast}).
 */
public final class Gui implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, GuiClickHandler> handlers;

    Gui(int rows, Component title, Map<Integer, ItemStack> items, Map<Integer, GuiClickHandler> handlers) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
        this.handlers = handlers;
        items.forEach(inventory::setItem);
    }

    public static GuiBuilder builder(int rows, Component title) {
        return new GuiBuilder(rows, title);
    }

    public static GuiBuilder builder(int rows, String title) {
        return new GuiBuilder(rows, Text.parse(title));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Replaces one slot's item/handler on an already-open GUI - e.g. showing an inline error without rebuilding the whole menu. */
    public void set(int slot, ItemStack item, GuiClickHandler handler) {
        inventory.setItem(slot, item);
        if (handler != null) {
            handlers.put(slot, handler);
        } else {
            handlers.remove(slot);
        }
    }

    void handleClick(Player player, InventoryClickEvent event) {
        GuiClickHandler handler = handlers.get(event.getSlot());
        if (handler != null) {
            handler.onClick(player, event);
        }
    }
}
