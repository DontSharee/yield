package me.dontshare.yieldlootboxes.data;

import java.util.Locale;

/**
 * What one {@link LootboxRewardEntry} grants. Deliberately no dedicated
 * POTION type - potions live in yield-achievements, and nothing else in this
 * codebase creates a cross-feature-module dependency like that; a box wanting
 * to grant one just uses {@link #COMMANDS} against the already-existing
 * "/admin potions give" command, same escape hatch yield-achievements' own
 * Store uses for everything it doesn't have a first-class field for.
 */
public enum LootboxRewardType {
    FLAT_COINS,
    FLAT_GEMS,
    FLAT_CREDITS,
    PET,
    COMMANDS;

    /** Parses lootboxes.yml's kebab-case "type" strings (e.g. "flat-coins") into this enum. */
    public static LootboxRewardType parse(String raw) {
        return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
