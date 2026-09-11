package me.dontshare.yieldlootboxes.data;

import java.util.List;

/** One weighted pool entry - only the field(s) relevant to {@code type} are ever populated (e.g. {@code petItemId} is null for every type except PET). */
public record LootboxRewardEntry(LootboxRewardType type, double weight, long amount, String petItemId, List<String> commands) {
}
