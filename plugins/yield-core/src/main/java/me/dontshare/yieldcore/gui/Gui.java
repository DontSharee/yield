package me.dontshare.yieldcore.gui;

import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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
    /** Chest rows, 0 for a fixed-shape GUI. */
    private final int rows;
    /** See {@link #applyFrame}. */
    private boolean framed;
    private Consumer<Player> closeHandler;
    /** Slots a player can freely place/remove real items into - see {@link #isEditableSlot}/{@link GuiListener}. Empty for every ordinary GUI (the default, fully server-controlled behavior). */
    private Set<Integer> editableSlots = Set.of();
    private Consumer<Player> editableSlotChangeHandler;
    /** Lets a player use their OWN inventory (pick up/swap items, switch hotbar slot) while this screen stays open - see {@link GuiBuilder#allowPlayerInventoryInteraction}. False (frozen, the default for every ordinary "click a button" GUI) unless a screen opts in. */
    private boolean allowPlayerInventoryInteraction;

    Gui(int rows, Component title, Map<Integer, ItemStack> items, Map<Integer, GuiClickHandler> handlers) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
        this.handlers = handlers;
        this.rows = rows;
        items.forEach(inventory::setItem);
    }

    /** A non-chest-shaped GUI (e.g. {@code InventoryType.DISPENSER}'s 3x3 grid) - everything else about a {@link Gui} (click cancellation, slot handlers) works identically regardless of shape. */
    Gui(InventoryType type, Component title, Map<Integer, ItemStack> items, Map<Integer, GuiClickHandler> handlers) {
        this.inventory = Bukkit.createInventory(this, type, title);
        this.handlers = handlers;
        this.rows = 0;
        items.forEach(inventory::setItem);
    }

    /** Standing rule: never color a GUI's inventory title - keep it plain text. Accent colors belong in icon lore, not here. */
    public static GuiBuilder builder(int rows, Component title) {
        return new GuiBuilder(rows, title);
    }

    /** See {@link #builder(int, Component)} - a plain string with no color/MiniMessage tags is expected here. */
    public static GuiBuilder builder(int rows, String title) {
        return new GuiBuilder(rows, Text.parse(title));
    }

    /** A fixed-shape GUI, e.g. {@code Gui.builder(InventoryType.DISPENSER, "...")} for a 3x3 grid whose center is slot 4 - same standing rule on titles as {@link #builder(int, Component)}. */
    public static GuiBuilder builder(InventoryType type, Component title) {
        return new GuiBuilder(type, title);
    }

    public static GuiBuilder builder(InventoryType type, String title) {
        return new GuiBuilder(type, Text.parse(title));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Replaces one slot's item/handler on an already-open GUI - e.g. showing an inline error without rebuilding the whole menu. */
    public void set(int slot, ItemStack item, GuiClickHandler handler) {
        inventory.setItem(slot, framed && handler == null && isInterior(slot) && GuiIcons.isFiller(item) ? null : item);
        if (handler != null) {
            handlers.put(slot, handler);
        } else {
            handlers.remove(slot);
        }
    }

    /** Returns whether a handler actually existed for this slot - lets {@link GuiListener} play a click sound only for real interactive slots, not filler/inert ones. */
    boolean handleClick(Player player, InventoryClickEvent event) {
        GuiClickHandler handler = handlers.get(event.getSlot());
        if (handler != null) {
            handler.onClick(player, event);
            return true;
        }
        return false;
    }

    /** Runs once whenever this specific Gui instance closes, for any reason - e.g. yield-auctionhouse uses this to stop tracking a player as an active Auction House browser once they leave the screen. */
    public void setCloseHandler(Consumer<Player> closeHandler) {
        this.closeHandler = closeHandler;
    }

    /**
     * The house frame: panes around the edge, nothing inside. Every screen
     * builds by filling first and placing second, so the panes a screen
     * put inside the frame are taken back out here, and any gap in the
     * edge is closed - one rule, applied the same way to every menu.
     * <p>
     * Skipped for screens with editable slots (there the panes are what
     * tell a player which empty slots take items and which don't) and for
     * inventory-style screens like the Bag, whose grid runs edge to edge.
     */
    void applyFrame() {
        if (rows < 3 || !editableSlots.isEmpty() || allowPlayerInventoryInteraction) {
            return;
        }
        framed = true;
        for (int slot = 0; slot < rows * 9; slot++) {
            ItemStack item = inventory.getItem(slot);
            boolean empty = item == null || item.getType().isAir();
            if (isInterior(slot)) {
                if (!empty && !handlers.containsKey(slot) && GuiIcons.isFiller(item)) {
                    inventory.setItem(slot, null);
                }
            } else if (empty) {
                inventory.setItem(slot, GuiIcons.filler());
            }
        }
    }

    private boolean isInterior(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        return row > 0 && row < rows - 1 && column > 0 && column < 8;
    }

    /** Called by {@link GuiListener} whenever this GUI closes, for any reason. */
    void handleClose(Player player) {
        if (closeHandler != null) {
            closeHandler.accept(player);
        }
    }

    void setEditableSlots(Set<Integer> editableSlots) {
        this.editableSlots = editableSlots;
    }

    void setEditableSlotChangeHandler(Consumer<Player> handler) {
        this.editableSlotChangeHandler = handler;
    }

    /** Whether a player can freely place/remove a real item at this top-inventory slot - see {@link GuiListener}, which skips its usual full-cancel for one of these. */
    boolean isEditableSlot(int slot) {
        return editableSlots.contains(slot);
    }

    boolean hasEditableSlots() {
        return !editableSlots.isEmpty();
    }

    void setAllowPlayerInventoryInteraction(boolean allow) {
        this.allowPlayerInventoryInteraction = allow;
    }

    boolean allowsPlayerInventoryInteraction() {
        return allowPlayerInventoryInteraction;
    }

    /** Called (deferred to the next tick, once the click/drag has actually resolved) whenever an editable slot's contents change - e.g. ForgeGui recomputes and re-renders its running total. */
    void notifyEditableSlotChanged(Player player) {
        if (editableSlotChangeHandler != null) {
            editableSlotChangeHandler.accept(player);
        }
    }
}
