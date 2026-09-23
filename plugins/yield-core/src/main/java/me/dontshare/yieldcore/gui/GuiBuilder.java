package me.dontshare.yieldcore.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/** Fluent builder for a {@link Gui} - declare every slot's item and (optionally) its click behavior, then {@link #build()}. */
public final class GuiBuilder {

    private final int rows;
    /** Null for a plain chest-shaped GUI (see {@code rows}); set for a fixed-shape one like {@code InventoryType.DISPENSER}. */
    private final InventoryType type;
    private final Component title;
    private final Map<Integer, ItemStack> items = new HashMap<>();
    private final Map<Integer, GuiClickHandler> handlers = new HashMap<>();
    private Consumer<Player> closeHandler;
    private final Set<Integer> editableSlots = new HashSet<>();
    private Consumer<Player> editableSlotChangeHandler;
    private boolean allowPlayerInventoryInteraction;

    GuiBuilder(int rows, Component title) {
        this.rows = rows;
        this.type = null;
        this.title = title;
    }

    GuiBuilder(InventoryType type, Component title) {
        this.rows = 0;
        this.type = type;
        this.title = title;
    }

    /** A static, non-interactive slot (a border pane, decoration, info display, etc). */
    public GuiBuilder item(int slot, ItemStack item) {
        items.put(slot, item);
        return this;
    }

    public GuiBuilder item(int slot, ItemStack item, GuiClickHandler onClick) {
        items.put(slot, item);
        handlers.put(slot, onClick);
        return this;
    }

    public GuiBuilder fill(IntStream slots, ItemStack item) {
        slots.forEach(slot -> item(slot, item));
        return this;
    }

    public GuiBuilder fill(IntStream slots, ItemStack item, GuiClickHandler onClick) {
        slots.forEach(slot -> item(slot, item, onClick));
        return this;
    }

    /** Called once when this GUI closes for any reason (a click that closed it itself included) - e.g. a "you didn't pick, so here's a default" fallback. */
    public GuiBuilder onClose(Consumer<Player> closeHandler) {
        this.closeHandler = closeHandler;
        return this;
    }

    /**
     * Marks these slots as genuinely editable - a player can freely place/
     * remove a real item there, same as any other chest slot, rather than
     * this GUI's usual fully-locked/button-only behavior (see
     * {@link GuiListener}). For a real drag-and-drop input grid (e.g.
     * yield-mining's Mining Forge) rather than a click-a-button screen -
     * leave every other slot as a plain {@link #item}/{@link #fill} button
     * as normal. Call {@link #onEditableSlotChange} to be notified once a
     * click/drag actually resolves.
     */
    public GuiBuilder editableSlots(IntStream slots) {
        slots.forEach(editableSlots::add);
        return this;
    }

    /** Fires once an editable slot's contents actually change (deferred to the next tick, once the click/drag has resolved) - e.g. to recompute and redraw a running total. */
    public GuiBuilder onEditableSlotChange(Consumer<Player> handler) {
        this.editableSlotChangeHandler = handler;
        return this;
    }

    /**
     * Leaves the player's OWN inventory (not this GUI's top inventory)
     * freely usable while this screen stays open - pick up/swap items,
     * switch hotbar slot, etc. Default false (frozen) for every ordinary
     * "click a button" GUI, matching every existing screen's behavior; a
     * screen whose buttons react to whatever the player is currently
     * holding (e.g. Bag's "click a pet while holding candy/a held item to
     * feed it") needs this so a player can actually change what's in their
     * hand without closing the menu first. A shift-click from the bottom
     * inventory is still blocked even so - its destination among this
     * GUI's own button slots would be ambiguous.
     */
    public GuiBuilder allowPlayerInventoryInteraction() {
        this.allowPlayerInventoryInteraction = true;
        return this;
    }

    public Gui build() {
        Gui gui = type != null ? new Gui(type, title, items, handlers) : new Gui(rows, title, items, handlers);
        gui.setCloseHandler(closeHandler);
        gui.setEditableSlots(editableSlots);
        gui.setEditableSlotChangeHandler(editableSlotChangeHandler);
        gui.setAllowPlayerInventoryInteraction(allowPlayerInventoryInteraction);
        gui.applyFrame();
        return gui;
    }
}
