package me.dontshare.yieldtrade.gui;

import me.dontshare.yieldtrade.session.TradeService;
import me.dontshare.yieldtrade.session.TradeSession;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Every interaction with a trade screen, handled from scratch.
 * <p>
 * <b>The rule this whole class is built on: no click is ever passed through
 * to vanilla.</b> Each one is cancelled first and then re-implemented here
 * against {@link TradeSession} state. That's what makes the usual inventory
 * duplication tricks inapplicable - shift-click, double-click
 * "collect to cursor", number-key and offhand hotbar swaps, and multi-slot
 * drags all resolve through vanilla's own container logic, which has no idea
 * two players are looking at the same trade and has historically been the
 * source of the desync bugs these exploits rely on. Nothing here ever puts
 * an item on the cursor at all, so there is no half-moved stack for a
 * mistimed or replayed packet to land on.
 * <p>
 * What players get instead is a click-to-offer model: click something in
 * your own inventory to put it up, click it again in the grid to take it
 * back. Shift-click is deliberately supported (it offers the whole stack,
 * the same as a left-click) rather than blocked, since a trade screen that
 * silently swallows shift-click feels broken.
 */
public final class TradeGuiListener implements Listener {

    private final TradeService tradeService;
    private final JavaPlugin plugin;

    public TradeGuiListener(TradeService tradeService, JavaPlugin plugin) {
        this.tradeService = tradeService;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TradeGui gui)) {
            return;
        }
        // Cancelled unconditionally, before any branch below can return early -
        // every path through this method is a no-op as far as vanilla is concerned.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        TradeSession session = gui.getSession();
        if (session.isFinished() || !session.involves(player.getUniqueId())) {
            return;
        }

        boolean clickedTop = event.getClickedInventory() == event.getView().getTopInventory();
        if (clickedTop) {
            handleTopClick(player, session, event.getSlot());
        } else if (event.getClickedInventory() != null) {
            handleOwnInventoryClick(player, session, event.getSlot(), event.isRightClick());
        }
    }

    /** A drag can span both inventories at once, so it's never allowed here. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TradeGui) {
            event.setCancelled(true);
        }
    }

    private void handleTopClick(Player player, TradeSession session, int slot) {
        if (slot == TradeGui.CANCEL_SLOT) {
            tradeService.cancel(session, "<red>Trade cancelled.</red>");
            return;
        }
        if (slot == TradeGui.ACCEPT_SLOT) {
            toggleAccept(player, session);
            return;
        }

        int offerIndex = TradeGui.ownOfferIndex(slot);
        if (offerIndex < 0) {
            // The partner's half of the screen, a divider, or their status
            // icon - all of it is someone else's or purely decorative.
            return;
        }
        if (!session.isBuilding()) {
            return;
        }
        ItemStack removed = session.removeItem(player.getUniqueId(), offerIndex);
        if (removed == null) {
            return;
        }
        giveOrDrop(player, removed);
        player.updateInventory();
        tradeService.persistEscrow(session);
        tradeService.renderBoth(session);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }

    /**
     * Offers what the player clicked in their own inventory. Left/shift-click
     * puts up the whole stack, right-click a single item - the stack is
     * physically removed here, so from this point the session holds the only
     * copy of it.
     */
    private void handleOwnInventoryClick(Player player, TradeSession session, int slot, boolean rightClick) {
        if (!session.isBuilding()) {
            return;
        }
        ItemStack clicked = player.getInventory().getItem(slot);
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        if (session.offerOf(player.getUniqueId()).size() >= TradeSession.MAX_OFFER_SLOTS) {
            player.sendMessage(me.dontshare.yieldcore.text.Text.parse("<red>You can't put up any more than that.</red>"));
            return;
        }

        ItemStack offered = clicked.clone();
        if (rightClick && clicked.getAmount() > 1) {
            offered.setAmount(1);
            clicked.setAmount(clicked.getAmount() - 1);
        } else {
            player.getInventory().setItem(slot, null);
        }

        if (!session.addItem(player.getUniqueId(), offered)) {
            // Refused after the item was already taken out - put it straight back.
            giveOrDrop(player, offered);
            player.updateInventory();
            return;
        }
        // Force the client back in sync with what the server just decided. A
        // client that lies about, delays, or replays its own inventory state
        // gets overwritten on every single interaction rather than being left
        // to drift into a desync worth exploiting.
        player.updateInventory();
        tradeService.persistEscrow(session);
        tradeService.renderBoth(session);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    private void toggleAccept(Player player, TradeSession session) {
        boolean nowAccepted = !session.hasAccepted(player.getUniqueId());
        session.setAccepted(player.getUniqueId(), nowAccepted);
        if (!nowAccepted && session.getState() == TradeSession.State.CONFIRMING) {
            session.resetAcceptance();
        }
        tradeService.onAcceptanceChanged(session);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, nowAccepted ? 1.5f : 0.8f);
    }

    /** Closing the screen ends the trade for both sides, as asked. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof TradeGui gui)) {
            return;
        }
        TradeSession session = gui.getSession();
        if (session.isFinished()) {
            return;
        }
        // Deferred a tick on purpose: handing items back while the close is
        // still being processed races the client's own close handling and can
        // leave the returned stack invisible until a relog.
        Bukkit.getScheduler().runTask(plugin, () ->
                tradeService.cancel(session, "<red>Trade cancelled - the other player closed the menu.</red>"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID quitterId = event.getPlayer().getUniqueId();
        TradeSession session = tradeService.sessionOf(quitterId);
        if (session != null) {
            // The leaver's own items go to their claim queue rather than into
            // an inventory that is already being saved out from under us.
            tradeService.cancel(session, "<red>Trade cancelled - the other player disconnected.</red>", quitterId);
        }
    }

    /** Dying with a trade open would otherwise drop the offered items twice over. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        TradeSession session = tradeService.sessionOf(event.getEntity().getUniqueId());
        if (session != null) {
            tradeService.cancel(session, "<red>Trade cancelled - someone died.</red>");
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }
}
