package me.dontshare.yieldtools;

import me.dontshare.yieldtools.gui.ToolsGui;
import org.bukkit.Bukkit;
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
}
