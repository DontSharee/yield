package me.dontshare.yieldtrade.session;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One live trade between two players, and the single source of truth for
 * what each side has put up.
 * <p>
 * <b>Nothing is ever stored in an inventory.</b> Offered items are held here,
 * in this object's own lists, having been taken out of the offering player's
 * inventory the moment they offered them; both trade screens are re-rendered
 * from these lists and contain only display copies. That is what makes the
 * usual container-based duplication tricks inapplicable rather than merely
 * blocked - there is no container holding a real item to desync from, to
 * shift-click out of during a lag spike, or to drop on the floor if the
 * screen closes at an awkward moment. Whatever is in these lists is the only
 * copy, and it goes to exactly one of the two players.
 */
public final class TradeSession {

    /** Items each side may put up - matches the 12 slots the screen draws per side. */
    public static final int MAX_OFFER_SLOTS = 12;

    public enum State {
        /** Either side may still add/remove items. */
        BUILDING,
        /** Both sides have accepted and the countdown is running - offers are frozen. */
        CONFIRMING,
        /** Terminal: items have been handed over, or handed back. */
        FINISHED
    }

    private final UUID sessionId = UUID.randomUUID();
    private final UUID firstId;
    private final UUID secondId;
    private final List<ItemStack> firstOffer = new ArrayList<>();
    private final List<ItemStack> secondOffer = new ArrayList<>();

    private final long openedAtMillis = System.currentTimeMillis();
    private boolean firstAccepted;
    private boolean secondAccepted;
    private State state = State.BUILDING;
    /** Counts down only while {@link State#CONFIRMING}; the swap runs when it hits zero. */
    private int confirmTicksRemaining;

    public TradeSession(UUID firstId, UUID secondId) {
        this.firstId = firstId;
        this.secondId = secondId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getFirstId() {
        return firstId;
    }

    public UUID getSecondId() {
        return secondId;
    }

    public State getState() {
        return state;
    }

    public boolean isBuilding() {
        return state == State.BUILDING;
    }

    public boolean isFinished() {
        return state == State.FINISHED;
    }

    public void markFinished() {
        this.state = State.FINISHED;
    }

    public boolean involves(UUID playerId) {
        return firstId.equals(playerId) || secondId.equals(playerId);
    }

    public UUID partnerOf(UUID playerId) {
        return firstId.equals(playerId) ? secondId : firstId;
    }

    public List<ItemStack> offerOf(UUID playerId) {
        return firstId.equals(playerId) ? firstOffer : secondOffer;
    }

    public boolean hasAccepted(UUID playerId) {
        return firstId.equals(playerId) ? firstAccepted : secondAccepted;
    }

    public boolean bothAccepted() {
        return firstAccepted && secondAccepted;
    }

    public int getConfirmTicksRemaining() {
        return confirmTicksRemaining;
    }

    public void tickConfirm() {
        confirmTicksRemaining--;
    }

    public void beginConfirming(int ticks) {
        this.state = State.CONFIRMING;
        this.confirmTicksRemaining = ticks;
    }

    /**
     * Adds to a side's offer. Refused once anything is locked in, so an item
     * can never appear after the other player has seen what they agreed to.
     */
    public boolean addItem(UUID playerId, ItemStack item) {
        List<ItemStack> offer = offerOf(playerId);
        if (!isBuilding() || offer.size() >= MAX_OFFER_SLOTS) {
            return false;
        }
        offer.add(item);
        resetAcceptance();
        return true;
    }

    /** Takes one item back off a side's offer, or null if that slot is empty/locked. */
    public ItemStack removeItem(UUID playerId, int index) {
        List<ItemStack> offer = offerOf(playerId);
        if (!isBuilding() || index < 0 || index >= offer.size()) {
            return null;
        }
        ItemStack removed = offer.remove(index);
        resetAcceptance();
        return removed;
    }

    public void setAccepted(UUID playerId, boolean accepted) {
        if (firstId.equals(playerId)) {
            firstAccepted = accepted;
        } else {
            secondAccepted = accepted;
        }
    }

    /**
     * Drops both acceptances and stops any running countdown. Called on every
     * change to either offer, so agreeing to one set of items can never carry
     * over to a different set - the classic "swap the item out after they
     * accept" trade scam.
     */
    public void resetAcceptance() {
        firstAccepted = false;
        secondAccepted = false;
        if (state == State.CONFIRMING) {
            state = State.BUILDING;
            confirmTicksRemaining = 0;
        }
    }

    public boolean isEmpty() {
        return firstOffer.isEmpty() && secondOffer.isEmpty();
    }

    public long getOpenedAtMillis() {
        return openedAtMillis;
    }
}
