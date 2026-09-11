package me.dontshare.yieldleaderboards.data;

/** One configured leaderboard - {@code hologramName} must match a hologram already created in-game via FancyHolograms. */
public record LeaderboardDefinition(String id, String hologramName, String title, String colorHex,
                                     String statId, long updateDelayMillis) {
}
