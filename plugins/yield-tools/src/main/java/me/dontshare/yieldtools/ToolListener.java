package me.dontshare.yieldtools;

import me.dontshare.yieldtools.gui.ToolsGui;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps the tool in hand and behaving like a tool, not whatever item it
 * happens to look like.
 * <p>
 * Right-click opens /tools; left-click is left alone, because the arm swing
 * it sends IS the tap. Several tools are also placeable blocks (a stone
 * button, a heavy core) or have a use (a trident throws), and a right-click
 * that did those instead of opening the menu would be a bug, so every
 * right-click with a tool is cancelled, and placing or dropping one is too.
 */
public final class ToolListener implements Listener {

    private final JavaPlugin plugin;
    private final ToolService tools;
    private final ToolItem toolItem;
    private final ToolsGui gui;

    public ToolListener(JavaPlugin plugin, ToolService tools, ToolItem toolItem, ToolsGui gui) {
        this.plugin = plugin;
        this.tools = tools;
        this.toolItem = toolItem;
        this.gui = gui;
    }

    /** A tick late, so it lands after every other join handler has set up its own hotbar items. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                tools.refreshItem(event.getPlayer());
            }
        });
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !toolItem.isTool(event.getItem())) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            gui.open(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (toolItem.isTool(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (toolItem.isTool(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        boolean touchesToolSlot = event.getClickedInventory() instanceof PlayerInventory
                && event.getSlot() == ToolService.TOOL_SLOT;
        boolean hotbarSwapIntoToolSlot = event.getClick() == ClickType.NUMBER_KEY
                && event.getHotbarButton() == ToolService.TOOL_SLOT;
        boolean offhandSwap = event.getClick() == ClickType.SWAP_OFFHAND
                && toolItem.isTool(event.getCurrentItem());
        if (touchesToolSlot || hotbarSwapIntoToolSlot || offhandSwap
                || toolItem.isTool(event.getCurrentItem()) || toolItem.isTool(event.getCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (toolItem.isTool(event.getOldCursor())) {
            event.setCancelled(true);
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (event.getView().getInventory(raw) instanceof PlayerInventory
                    && event.getView().convertSlot(raw) == ToolService.TOOL_SLOT) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (toolItem.isTool(event.getMainHandItem()) || toolItem.isTool(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }
}
