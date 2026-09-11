package me.dontshare.yieldpacks.selector;

import me.dontshare.yieldpacks.gui.BagGui;
import org.bukkit.GameMode;
import org.bukkit.Sound;
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
 * Wires the Bag shortcut item into Bukkit events - same shape as
 * {@link PackSelectorListener}, just pinned to slot 3 (right next to the
 * Pack Selector's slot 4) and a single action (any click opens the Bag)
 * instead of a left/right-click split.
 */
public final class BagSelectorListener implements Listener {

    public static final int SLOT = 3;

    private final BagSelectorItem item;
    private final BagGui bagGui;

    public BagSelectorListener(BagSelectorItem item, BagGui bagGui) {
        this.item = item;
        this.bagGui = bagGui;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ensureItem(event.getPlayer());
    }

    private void ensureItem(Player player) {
        if (!item.isBagSelector(player.getInventory().getItem(SLOT))) {
            player.getInventory().setItem(SLOT, item.create());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !item.isBagSelector(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK
                || action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            bagGui.open(event.getPlayer());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        boolean touchesSlot = event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == SLOT;
        boolean hotbarSwapIntoSlot = event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() == SLOT;
        if (touchesSlot || hotbarSwapIntoSlot
                || item.isBagSelector(event.getCurrentItem()) || item.isBagSelector(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (item.isBagSelector(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (item.isBagSelector(event.getItemDrop().getItemStack())) {
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
        if (item.isBagSelector(main) || item.isBagSelector(off)) {
            event.setCancelled(true);
        }
    }
}
