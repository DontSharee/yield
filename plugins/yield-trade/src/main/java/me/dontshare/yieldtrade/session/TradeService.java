package me.dontshare.yieldtrade.session;

import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldtrade.gui.TradeGui;
import me.dontshare.yieldtrade.store.TradeClaimStore;
import me.dontshare.yieldtrade.store.TradeEscrowStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every live trade: who is in one, the countdown, and the single
 * hand-over step at the end.
 * <p>
 * The swap itself ({@link #execute}) is one uninterrupted main-thread block
 * with no I/O in it at all. Both offers are already sitting in the
 * {@link TradeSession} - taken out of their owners' inventories when they
 * were offered - so completing a trade is purely "give list A to player B and
 * list B to player A", with nothing to roll back partway and no window where
 * an item exists in two places. Anything that goes wrong before that point
 * (either player leaving, dying, closing the screen, the server stopping)
 * takes the {@link #cancel} path, which gives every offered item back to
 * whoever offered it.
 */
public final class TradeService {

    /** How long both sides must stay accepted before the swap runs. */
    private static final int CONFIRM_TICKS = 20 * 3;
    /** How long an unanswered {@code /trade} invite stays open. */
    private static final long REQUEST_TIMEOUT_MILLIS = 60_000L;
    /**
     * A trade nobody finishes is closed out rather than left holding items
     * indefinitely. Measured from when the screen opened and never extended,
     * so it's set well past how long any real negotiation takes.
     */
    private static final long SESSION_TIMEOUT_MILLIS = 15 * 60_000L;
    /** Trading is gated behind this many rebirths, on both sides. */
    public static final int REQUIRED_REBIRTHS = 50;

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final DatabaseManager databaseManager;
    private final TradeEscrowStore escrowStore;
    private final TradeClaimStore claimStore;

    private final Map<UUID, TradeSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, TradeGui> guisByPlayer = new ConcurrentHashMap<>();
    /** target -> (requester -> sent at). */
    private final Map<UUID, Map<UUID, Long>> pendingRequests = new ConcurrentHashMap<>();

    public TradeService(JavaPlugin plugin, PlayerDataStore<PackPlayerProfile> playerStore,
                        DatabaseManager databaseManager, TradeEscrowStore escrowStore, TradeClaimStore claimStore) {
        this.plugin = plugin;
        this.playerStore = playerStore;
        this.databaseManager = databaseManager;
        this.escrowStore = escrowStore;
        this.claimStore = claimStore;
    }

    /** Mirrors the current offers so a hard crash can't take them - see {@link TradeEscrowStore}. */
    public void persistEscrow(TradeSession session) {
        UUID sessionId = session.getSessionId();
        UUID firstId = session.getFirstId();
        UUID secondId = session.getSecondId();
        List<ItemStack> firstOffer = List.copyOf(session.offerOf(firstId));
        List<ItemStack> secondOffer = List.copyOf(session.offerOf(secondId));
        databaseManager.supplyAsync(() -> {
            escrowStore.save(sessionId, firstId, firstOffer, secondId, secondOffer);
            return true;
        });
    }

    private void clearEscrow(UUID sessionId) {
        databaseManager.supplyAsync(() -> {
            escrowStore.delete(sessionId);
            return true;
        });
    }

    public void startConfirmTicker() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickConfirmations, 1L, 1L);
    }

    public TradeSession sessionOf(UUID playerId) {
        return sessionsByPlayer.get(playerId);
    }

    public TradeGui guiOf(UUID playerId) {
        return guisByPlayer.get(playerId);
    }

    public boolean isTrading(UUID playerId) {
        return sessionsByPlayer.containsKey(playerId);
    }

    /** How many rebirths this player is short of being allowed to trade, or 0 if they're eligible. */
    public int rebirthsShort(UUID playerId) {
        PackPlayerProfile profile = playerStore.getCached(playerId);
        if (profile == null) {
            return REQUIRED_REBIRTHS;
        }
        return Math.max(0, REQUIRED_REBIRTHS - profile.getRebirths());
    }

    // ------------------------------------------------------------- Requests

    public void sendRequest(Player requester, Player target) {
        pendingRequests.computeIfAbsent(target.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .put(requester.getUniqueId(), System.currentTimeMillis());

        requester.sendMessage(Text.parse("<green>Trade request sent to <name>.</green>",
                Placeholder.unparsed("name", target.getName())));
        target.sendMessage(Text.parse(
                "<#4BD9FF><bold>Trade</bold> <dark_gray>»</dark_gray> <white><name></white> <gray>wants to trade. "
                        + "Type <yellow>/trade accept <name></yellow> <gray>to open it.</gray>",
                Placeholder.unparsed("name", requester.getName())));
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
    }

    public boolean hasPendingRequest(UUID target, UUID requester) {
        Map<UUID, Long> requests = pendingRequests.get(target);
        if (requests == null) {
            return false;
        }
        Long sentAt = requests.get(requester);
        if (sentAt == null) {
            return false;
        }
        if (System.currentTimeMillis() - sentAt > REQUEST_TIMEOUT_MILLIS) {
            requests.remove(requester);
            return false;
        }
        return true;
    }

    public void clearRequest(UUID target, UUID requester) {
        Map<UUID, Long> requests = pendingRequests.get(target);
        if (requests != null) {
            requests.remove(requester);
        }
    }

    // -------------------------------------------------------------- Opening

    /** Opens the screen for both players. Callers must have already checked eligibility for both. */
    public void open(Player first, Player second) {
        TradeSession session = new TradeSession(first.getUniqueId(), second.getUniqueId());
        sessionsByPlayer.put(first.getUniqueId(), session);
        sessionsByPlayer.put(second.getUniqueId(), session);

        TradeGui firstGui = new TradeGui(session, first.getUniqueId(), second.getName());
        TradeGui secondGui = new TradeGui(session, second.getUniqueId(), first.getName());
        guisByPlayer.put(first.getUniqueId(), firstGui);
        guisByPlayer.put(second.getUniqueId(), secondGui);

        firstGui.render();
        secondGui.render();
        first.openInventory(firstGui.getInventory());
        second.openInventory(secondGui.getInventory());
    }

    /** Repaints both sides - call after any change to session state. */
    public void renderBoth(TradeSession session) {
        for (UUID id : List.of(session.getFirstId(), session.getSecondId())) {
            TradeGui gui = guisByPlayer.get(id);
            if (gui != null) {
                gui.render();
            }
        }
    }

    // ------------------------------------------------------------ Countdown

    public void onAcceptanceChanged(TradeSession session) {
        if (session.bothAccepted() && session.getState() == TradeSession.State.BUILDING) {
            if (session.isEmpty()) {
                // Nothing on either side - accepting an empty trade is almost
                // always a misclick, and letting it "complete" just teaches
                // players the confirm step can be raced through.
                session.resetAcceptance();
                renderBoth(session);
                broadcast(session, "<red>Put something up before accepting.</red>");
                return;
            }
            session.beginConfirming(CONFIRM_TICKS);
        }
        renderBoth(session);
    }

    private void tickConfirmations() {
        for (TradeSession session : Set.copyOf(sessionsByPlayer.values())) {
            if (System.currentTimeMillis() - session.getOpenedAtMillis() > SESSION_TIMEOUT_MILLIS) {
                cancel(session, "<red>Trade timed out.</red>");
                continue;
            }
            if (session.getState() != TradeSession.State.CONFIRMING) {
                continue;
            }
            session.tickConfirm();
            if (session.getConfirmTicksRemaining() <= 0) {
                execute(session);
            } else if (session.getConfirmTicksRemaining() % 20 == 0) {
                renderBoth(session);
            }
        }
    }

    // -------------------------------------------------------------- Finish

    /**
     * Hands both offers over. Runs entirely on the main thread in one pass,
     * and only once - {@link TradeSession#markFinished} is set before
     * anything moves, so a second call (a duplicate tick, a racing click)
     * finds a finished session and does nothing.
     */
    public void execute(TradeSession session) {
        if (session.isFinished()) {
            return;
        }
        Player first = Bukkit.getPlayer(session.getFirstId());
        Player second = Bukkit.getPlayer(session.getSecondId());
        if (first == null || second == null) {
            // Someone left between accepting and the swap - hand everything back.
            cancel(session, "<red>Trade cancelled - the other player left.</red>");
            return;
        }

        List<ItemStack> firstOffer = List.copyOf(session.offerOf(session.getFirstId()));
        List<ItemStack> secondOffer = List.copyOf(session.offerOf(session.getSecondId()));
        if (!hasRoomFor(first, secondOffer) || !hasRoomFor(second, firstOffer)) {
            session.resetAcceptance();
            renderBoth(session);
            broadcast(session, "<red>Both players need enough free inventory space - trade paused.</red>");
            return;
        }

        session.markFinished();
        session.offerOf(session.getFirstId()).clear();
        session.offerOf(session.getSecondId()).clear();

        secondOffer.forEach(item -> giveOrDrop(first, item));
        firstOffer.forEach(item -> giveOrDrop(second, item));

        clearEscrow(session.getSessionId());
        forget(session);
        for (Player player : List.of(first, second)) {
            player.closeInventory();
            player.sendMessage(Text.parse("<green><bold>Trade complete!</bold></green>"));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
        }
        plugin.getLogger().info("Trade " + session.getSessionId() + " completed between "
                + first.getName() + " (" + firstOffer.size() + " items) and "
                + second.getName() + " (" + secondOffer.size() + " items).");
    }

    /** Ends a trade without swapping, returning every offered item to whoever offered it. */
    public void cancel(TradeSession session, String reason) {
        cancel(session, reason, null);
    }

    /**
     * @param forceClaimFor a player whose items must go to {@link TradeClaimStore} rather than
     *                      straight into their inventory - used for someone who is mid-disconnect,
     *                      where an inventory write races the save that's already happening
     */
    public void cancel(TradeSession session, String reason, UUID forceClaimFor) {
        if (session.isFinished()) {
            return;
        }
        session.markFinished();

        returnOffer(session, session.getFirstId(), forceClaimFor);
        returnOffer(session, session.getSecondId(), forceClaimFor);
        clearEscrow(session.getSessionId());
        forget(session);

        for (UUID id : List.of(session.getFirstId(), session.getSecondId())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(Text.parse(reason));
                player.closeInventory();
            }
        }
    }

    /**
     * Returns one side's offer. If they've already gone, the items become
     * {@link TradeClaimStore} rows waiting for their next login rather than
     * being dropped on the ground where anyone could take them.
     */
    private void returnOffer(TradeSession session, UUID playerId, UUID forceClaimFor) {
        List<ItemStack> offer = session.offerOf(playerId);
        if (offer.isEmpty()) {
            return;
        }
        Player player = playerId.equals(forceClaimFor) ? null : Bukkit.getPlayer(playerId);
        if (player != null) {
            offer.forEach(item -> giveOrDrop(player, item));
        } else {
            List<ItemStack> owed = List.copyOf(offer);
            plugin.getLogger().warning("Trade " + session.getSessionId() + ": " + playerId
                    + " went offline holding " + owed.size() + " offered item(s); queued for their next login.");
            databaseManager.supplyAsync(() -> {
                claimStore.insert(playerId, owed, "trade-cancelled");
                return true;
            });
        }
        offer.clear();
    }

    private void forget(TradeSession session) {
        sessionsByPlayer.remove(session.getFirstId());
        sessionsByPlayer.remove(session.getSecondId());
        guisByPlayer.remove(session.getFirstId());
        guisByPlayer.remove(session.getSecondId());
    }

    /** Ends every live trade - used at shutdown so nothing is left holding items. */
    public void cancelAll(String reason) {
        for (TradeSession session : Set.copyOf(sessionsByPlayer.values())) {
            cancel(session, reason);
        }
    }

    public void broadcast(TradeSession session, String miniMessage) {
        for (UUID id : List.of(session.getFirstId(), session.getSecondId())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(Text.parse(miniMessage));
            }
        }
    }

    private boolean hasRoomFor(Player player, List<ItemStack> incoming) {
        Map<Integer, ItemStack> leftover = new HashMap<>();
        org.bukkit.inventory.Inventory probe = Bukkit.createInventory(null, 36);
        probe.setContents(player.getInventory().getStorageContents());
        for (ItemStack item : incoming) {
            leftover.putAll(probe.addItem(item.clone()));
        }
        return leftover.isEmpty();
    }

    private void giveOrDrop(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }
}
