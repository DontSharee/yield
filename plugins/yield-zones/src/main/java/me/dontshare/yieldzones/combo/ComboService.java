package me.dontshare.yieldzones.combo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The moment-to-moment "keep swinging" loop - every cube kill within {@link
 * #WINDOW_MILLIS} of the last one extends a per-player combo streak (reset
 * to 1 the instant that window lapses), which both boosts the coin payout
 * a little (capped, so it stays a nice-to-have rather than a required
 * grind pattern) and gets its own escalating fanfare at real milestones.
 * Entirely in-memory, never persisted - a combo is meant to live and die
 * within one active fighting session, not carry meaning across logins.
 */
public final class ComboService {

    private static final long WINDOW_MILLIS = 4000L;
    private static final double BONUS_PER_STACK = 0.01;
    private static final double MAX_BONUS = 0.50;
    private static final int[] MILESTONES = {10, 25, 50, 100, 250, 500, 1000};

    public record ComboResult(int count, boolean milestone) {
    }

    private final Map<UUID, Long> lastKillAtMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> comboByPlayer = new ConcurrentHashMap<>();

    /** Call exactly once per kill - advances (or resets) this player's own streak and reports the result. */
    public ComboResult recordKill(UUID playerId) {
        long now = System.currentTimeMillis();
        long last = lastKillAtMillis.getOrDefault(playerId, 0L);
        int count = (now - last <= WINDOW_MILLIS) ? comboByPlayer.getOrDefault(playerId, 0) + 1 : 1;
        lastKillAtMillis.put(playerId, now);
        comboByPlayer.put(playerId, count);
        return new ComboResult(count, isMilestone(count));
    }

    /** {@code 1.0 + Σ}, capped at {@link #MAX_BONUS} - the combo's own coin-payout bonus, same "1.0 +" multiplier shape every other stat in this codebase uses. */
    public double bonusMultiplier(int comboCount) {
        return 1.0 + Math.min(MAX_BONUS, Math.max(0, comboCount - 1) * BONUS_PER_STACK);
    }

    public void clear(UUID playerId) {
        lastKillAtMillis.remove(playerId);
        comboByPlayer.remove(playerId);
    }

    private boolean isMilestone(int count) {
        for (int milestone : MILESTONES) {
            if (milestone == count) {
                return true;
            }
        }
        return false;
    }
}
