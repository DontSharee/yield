package me.dontshare.yieldcore.gui;

import java.util.Comparator;

/** One named sort mode for a {@link SortButton} - a label plus the comparator it applies. */
public record SortOption<T>(String displayName, Comparator<T> comparator) {
}
