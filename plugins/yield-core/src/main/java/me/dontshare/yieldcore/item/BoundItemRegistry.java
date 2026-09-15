package me.dontshare.yieldcore.item;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * The server-wide list of "this one never leaves the player who owns it"
 * checks - the Pack Selector compass, the Bag chest, and anything else a
 * plugin pins into a fixed inventory slot.
 * <p>
 * Each of those items already has its owning plugin cancelling the events
 * that would move it (click, drag, drop, hotbar swap), and for anything
 * driven by vanilla item movement that is enough. It is <b>not</b> enough
 * for a system that moves items itself: yield-trade cancels every click and
 * then re-implements it against its own session state, and the Auction
 * House reads the seller's held stack straight out of their hand - neither
 * of them ever gives another plugin's cancel a chance to apply. Those
 * systems ask here instead.
 * <p>
 * Registration happens in each owning plugin's {@code onEnable}; yield-core
 * enables first, so the registry exists before anyone can add to it and is
 * fully populated long before a player can click anything.
 */
public final class BoundItemRegistry {

    /** Copy-on-write because it is written a handful of times at startup and then read from click handlers forever. */
    private final List<Predicate<ItemStack>> predicates = new CopyOnWriteArrayList<>();

    /** Call from your own onEnable, once per kind of bound item you hand out. */
    public void register(Predicate<ItemStack> predicate) {
        predicates.add(predicate);
    }

    /** True if any plugin claims this item as bound - i.e. it must not be tradeable, sellable, or otherwise transferable. */
    public boolean isBound(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        // Indexed rather than for-each: every one of these predicates reads
        // a persistent-data key off the item's meta, and the list is tiny,
        // so the iterator allocation is a real share of the cost.
        for (int i = 0, size = predicates.size(); i < size; i++) {
            if (predicates.get(i).test(item)) {
                return true;
            }
        }
        return false;
    }
}
