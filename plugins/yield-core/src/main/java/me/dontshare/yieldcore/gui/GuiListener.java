package me.dontshare.yieldcore.gui;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The single listener for every open {@link Gui} - recovers which one from
 * {@code event.getInventory().getHolder()} (Gui is its own holder) rather
 * than needing a per-GUI listener registration. Cancels every click/drag
 * against a Gui's top inventory by default (nothing is takeable/movable
 * except through a slot's own {@link GuiClickHandler}), and only dispatches
 * clicks that land on the Gui itself, not the player's own inventory below it.
 * <p>
 * The one exception is a GUI with {@link GuiBuilder#editableSlots} - e.g. a
 * real drag-and-drop input grid (yield-mining's Mining Forge). For one of
 * those, a click/drag entirely confined to editable top slots and/or the
 * player's own inventory is left completely alone (normal vanilla item
 * movement); a shift-click from the player's own inventory is routed
 * ourselves into the first free (or stackable) editable slot rather than
 * either trusting vanilla's own destination guess (which would consider
 * every top slot fair game, not just the editable ones) or blocking the
 * gesture outright. {@link Gui#notifyEditableSlotChanged} then fires next
 * tick, once the movement has actually resolved. A GUI with
 * {@link GuiBuilder#allowPlayerInventoryInteraction} but no editable slots
 * (e.g. Bag's "click a pet while holding candy") leaves the bottom
 * inventory alone the same way, but still blocks its shift-click (nothing
 * to receive it) and never fires the editable-slot notification.
 * <p>
 * Every real (handler-bound) click also gets a quiet UI sound here - one
 * place, so every GUI across every plugin gets it for free rather than each
 * screen needing to remember to play its own.
 */
public final class GuiListener implements Listener {

    private final GuiManager manager;
    private final JavaPlugin plugin;

    public GuiListener(GuiManager manager, JavaPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        boolean clickedTop = event.getClickedInventory() == event.getView().getTopInventory();

        // "Collect to cursor" (double-click) is the one vanilla action that
        // ignores which slots a screen considers its own: it sweeps every
        // matching stack out of BOTH inventories, including the locked
        // button/filler slots this listener otherwise guarantees are
        // untouchable. It is only refused when it would actually reach one
        // of those - a double-click that can only pull from editable slots
        // and the player's own inventory is left to resolve normally, and
        // falls through to the usual handling below. Blanket-cancelling it
        // broke real drag-and-drop on the Forge grid: players double-click
        // to gather a stack as part of the same gesture, and a cancel here
        // desyncs the cursor for every click after it.
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR && wouldCollectFromLockedSlot(gui, event)) {
            event.setCancelled(true);
            resyncNextTick(event.getWhoClicked());
            return;
        }

        if (clickedTop && gui.hasEditableSlots() && gui.isEditableSlot(event.getSlot())) {
            notifyNextTick(gui, event.getWhoClicked());
            return;
        }
        if (!clickedTop && (gui.hasEditableSlots() || gui.allowsPlayerInventoryInteraction())) {
            if (event.isShiftClick()) {
                if (gui.hasEditableSlots() && event.getWhoClicked() instanceof Player player) {
                    // Vanilla's own shift-click would consider EVERY top slot a valid destination,
                    // not just the editable ones - route it ourselves into the first free editable
                    // slot instead of either guessing wrong or blocking a gesture the player wants.
                    event.setCancelled(true);
                    moveShiftClickedItemIntoEditableSlots(gui, event);
                    notifyNextTick(gui, player);
                } else {
                    // No editable slots to receive it - its destination among this GUI's own locked button slots would be ambiguous.
                    event.setCancelled(true);
                }
            } else if (gui.hasEditableSlots()) {
                notifyNextTick(gui, event.getWhoClicked());
            }
            return;
        }

        event.setCancelled(true);
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            resyncNextTick(event.getWhoClicked());
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!clickedTop) {
            return;
        }
        if (gui.handleClick(player, event)) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        if (gui.hasEditableSlots()) {
            Inventory top = event.getView().getTopInventory();
            boolean onlyTouchesEditableOrBottom = event.getRawSlots().stream()
                    .allMatch(rawSlot -> rawSlot >= top.getSize() || gui.isEditableSlot(rawSlot));
            if (onlyTouchesEditableOrBottom) {
                notifyNextTick(gui, event.getWhoClicked());
                return;
            }
        }
        event.setCancelled(true);
    }

    /**
     * Whether a "collect to cursor" from here could pull an item out of a
     * slot this screen locks - the only case in which the gesture is worth
     * refusing. Filler panes and button icons are built with their own meta
     * (hidden tooltips, custom names), so a player holding a plain stack of
     * the same material does not match one.
     */
    private boolean wouldCollectFromLockedSlot(Gui gui, InventoryClickEvent event) {
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return false;
        }
        Inventory top = event.getView().getTopInventory();
        for (int slot = 0, size = top.getSize(); slot < size; slot++) {
            if (gui.isEditableSlot(slot)) {
                continue;
            }
            ItemStack existing = top.getItem(slot);
            if (existing != null && !existing.getType().isAir() && existing.isSimilar(cursor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A cancelled double-click is the one gesture the client does not roll
     * back on its own - it keeps drawing the stack it thinks it collected,
     * and every click after that lands on a slot the server disagrees
     * about. Pushed a tick later so it isn't overwritten by the container
     * update vanilla sends while still handling this same packet.
     */
    private void resyncNextTick(HumanEntity whoClicked) {
        if (whoClicked instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        }
    }

    /** Moves as much of the shift-clicked stack as fits into the first free (or same-item, stackable) editable slot(s), in slot order - leaves whatever doesn't fit in the source slot rather than losing it. */
    private void moveShiftClickedItemIntoEditableSlots(Gui gui, InventoryClickEvent event) {
        ItemStack source = event.getCurrentItem();
        if (source == null || source.getType().isAir()) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        for (int slot = 0; slot < top.getSize() && !source.getType().isAir(); slot++) {
            if (!gui.isEditableSlot(slot)) {
                continue;
            }
            ItemStack existing = top.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                top.setItem(slot, source.clone());
                event.setCurrentItem(null);
                return;
            }
            if (existing.isSimilar(source) && existing.getAmount() < existing.getMaxStackSize()) {
                int room = existing.getMaxStackSize() - existing.getAmount();
                int move = Math.min(room, source.getAmount());
                existing.setAmount(existing.getAmount() + move);
                source.setAmount(source.getAmount() - move);
            }
        }
        event.setCurrentItem(source.getAmount() <= 0 ? null : source);
    }

    private void notifyNextTick(Gui gui, HumanEntity whoClicked) {
        if (whoClicked instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> gui.notifyEditableSlotChanged(player));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getView().getTopInventory().getHolder() instanceof Gui gui) {
            manager.forget(player.getUniqueId(), gui);
            gui.handleClose(player);
        }
    }

    /** MONITOR: after the disconnect's own inventory close has run its handler. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        manager.forgetPlayer(event.getPlayer().getUniqueId());
    }
}
