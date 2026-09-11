package me.dontshare.yieldpacks.selector;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Wires the Pack Selector item into Bukkit events: gives/restores it on
 * join, translates left/right-click into PackSelectorService calls, and
 * blocks every normal way of moving or losing it outside Creative (direct
 * click, hotbar-swap, drag, drop, offhand-swap). Not airtight against every
 * exotic inventory-manipulation path, but {@link PackSelectorService#ensureItem}
 * running again on next join is the safety net for whatever slips through.
 */
public final class PackSelectorListener implements Listener {

    private final PackSelectorService service;
    private final PackSelectorItem item;

    public PackSelectorListener(PackSelectorService service, PackSelectorItem item) {
        this.service = service;
        this.item = item;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.ensureItem(event.getPlayer());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !item.isPackSelector(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            service.openSelectMenu(event.getPlayer());
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            service.attemptOpen(event.getPlayer());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        boolean touchesSelectorSlot = event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == 4;
        boolean hotbarSwapIntoSelectorSlot = event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == 4;
        if (touchesSelectorSlot || hotbarSwapIntoSelectorSlot
                || item.isPackSelector(event.getCurrentItem()) || item.isPackSelector(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (item.isPackSelector(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (item.isPackSelector(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack main = event.getMainHandItem();
        ItemStack off = event.getOffHandItem();
        if (item.isPackSelector(main) || item.isPackSelector(off)) {
            event.setCancelled(true);
        }
    }
}
