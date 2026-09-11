package me.dontshare.yieldspawnnpcs.crate;

import java.util.List;

/**
 * One weighted slot in a {@link CrateDefinition}'s pool - same reward-type
 * shape as {@code yield-lootboxes}' own {@code LootboxRewardEntry} (deliberately
 * NOT shared/reused - see the plan's own note on why: lootboxes are real-money-
 * only by explicit design, "not bought with in-game coins/gems"; Crates are
 * the in-game-currency counterpart, kept as a fully separate config/content
 * so the real-money pool's own value is never diluted by an in-game price).
 */
public record CrateRewardEntry(CrateRewardType type, double weight, long amount, String petItemId, List<String> commands) {
}
