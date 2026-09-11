package me.dontshare.yieldpacks.pet;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A pluggable "apply this held item to a pet" gesture - see
 * {@code YieldPacks#registerPetItemHandler}/{@code applyPetItemHandlers}.
 * Tried, in registration order, by both {@code BagGui} (clicking a pet's
 * icon while holding an item) and {@code PetItemFeedListener} (combining a
 * held item with a withdrawn pet sitting loose in an inventory) - the same
 * gesture works identically for an equipped pet, a bagged one, or a
 * withdrawn one, since all three ultimately hand this a real
 * {@link PetInstance} to mutate.
 */
public interface PetItemHandler {

    /**
     * Attempts to apply {@code heldItem} to {@code pet}. If this item type
     * is recognized: mutate {@code pet}'s own fields directly, decrement
     * {@code heldItem}'s amount by however many units were consumed
     * (always 1 for every handler so far), and return true - the caller
     * takes care of persisting {@code pet}'s new state (a profile save for
     * an equipped/bagged pet, or re-serializing a withdrawn item). Return
     * false immediately, without side effects, for anything else - the
     * caller tries the next registered handler, or falls back to whatever
     * the click/combine would otherwise have done.
     */
    boolean apply(Player player, PetInstance pet, ItemStack heldItem);
}
