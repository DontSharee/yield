package me.dontshare.yieldcore.gui;

import java.util.List;

/**
 * An immutable page-window slice of a list, for paginated GUI grids. Pure
 * math only - the caller is responsible for remembering each player's
 * current page index between renders (see {@link SortButton} for the
 * sibling "cycle a mode" pattern, which does the same per-player bookkeeping
 * for sort state).
 */
public record Page<T>(List<T> items, int index, int totalPages) {

    public static <T> Page<T> of(List<T> all, int index, int pageSize) {
        int totalPages = Math.max(1, (all.size() + pageSize - 1) / pageSize);
        int clampedIndex = Math.max(0, Math.min(index, totalPages - 1));
        int from = Math.min(clampedIndex * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return new Page<>(all.subList(from, to), clampedIndex, totalPages);
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    public boolean hasNext() {
        return index < totalPages - 1;
    }
}
