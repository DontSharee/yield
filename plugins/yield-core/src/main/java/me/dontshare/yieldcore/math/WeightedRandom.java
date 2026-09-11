package me.dontshare.yieldcore.math;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

/**
 * The "sum the weights, roll into [0, total), walk the cumulative sum until
 * it passes the roll" pick every loot table in this codebase wanted its own
 * copy of - pack pools, lootboxes, crates, and pet-enchant rolls all
 * hand-duplicated the same dozen lines. Floating-point rounding can
 * occasionally leave the walk short of the last element (a roll landing at
 * total - epsilon against weights whose doubles don't sum back exactly) -
 * every original call site already fell back to the last element for that
 * case, so this does too rather than throwing.
 */
public final class WeightedRandom {

    private WeightedRandom() {
    }

    /** Weighted pick from {@code items}, keyed by {@code weightOf}. Null if {@code items} is empty or every weight is <= 0. */
    public static <T> T pick(List<T> items, ToDoubleFunction<T> weightOf) {
        if (items.isEmpty()) {
            return null;
        }
        double total = 0;
        for (T item : items) {
            total += weightOf.applyAsDouble(item);
        }
        if (total <= 0) {
            return null;
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double cumulative = 0;
        for (T item : items) {
            cumulative += weightOf.applyAsDouble(item);
            if (roll < cumulative) {
                return item;
            }
        }
        return items.get(items.size() - 1);
    }

    /**
     * Same pick, for callers whose weights already live in a separate
     * parallel list rather than as a method on the item type itself (e.g. a
     * pool mixing several unrelated candidate types with no shared
     * "weight()" accessor). {@code items} and {@code weights} must be the
     * same size.
     */
    public static <T> T pick(List<T> items, List<Double> weights) {
        if (items.isEmpty()) {
            return null;
        }
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        if (total <= 0) {
            return null;
        }
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < items.size(); i++) {
            cumulative += weights.get(i);
            if (roll < cumulative) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }
}
