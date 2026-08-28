package me.dontshare.yieldpacks.data;

/** One weighted entry in a pack's loot pool - the raw config weight, before luck adjustment. */
public record PackPoolEntry(String itemId, double weight) {
}
