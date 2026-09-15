package me.dontshare.yieldquests;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldquests.data.QuestProfile;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.time.LocalDate;

/**
 * A real, persisted, calendar-day login streak - previously nothing like
 * this existed anywhere (the {@code /daily} presents chain is session-
 * length-based and resets on disconnect, not day-over-day). Escalates
 * through a 7-day cycle then loops (day 8 = day 1's reward again), so
 * there's always a reason to come back tomorrow no matter how long a
 * player's overall streak has grown - the streak COUNT itself keeps
 * climbing forever for bragging rights even once the reward cycle repeats.
 */
public final class LoginStreakService {

    private static final int CYCLE_LENGTH = 7;

    public record StreakResult(int streak, int dayInCycle, long coinsGranted, long diamondsGranted, long creditsGranted) {
        public boolean alreadyCreditedToday() {
            return coinsGranted == 0 && diamondsGranted == 0 && creditsGranted == 0;
        }
    }

    private final YieldPacks packs;
    /** This plugin's own streak state. The pack store stays for the rewards a streak day grants. */
    private final PlayerDataStore<QuestProfile> questStore;

    public LoginStreakService(YieldPacks packs, PlayerDataStore<QuestProfile> questStore) {
        this.packs = packs;
        this.questStore = questStore;
    }

    /** Call once per join - a no-op reward (but still reports the current streak) if this player already logged in today, so relogging never double-grants. */
    public StreakResult recordLogin(Player player) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        QuestProfile quests = questStore.getOrCreate(player.getUniqueId());
        long today = LocalDate.now().toEpochDay();
        long last = quests.getLastLoginEpochDay();

        if (last == today) {
            int streak = Math.max(1, quests.getLoginStreak());
            return new StreakResult(streak, dayInCycle(streak), 0, 0, 0);
        }

        int streak = (last == today - 1) ? quests.getLoginStreak() + 1 : 1;
        quests.setLoginStreak(streak);
        quests.setLastLoginEpochDay(today);
        questStore.save(player.getUniqueId());

        int dayInCycle = dayInCycle(streak);
        long coins = coinsFor(dayInCycle);
        long diamonds = diamondsFor(dayInCycle);
        long credits = dayInCycle == CYCLE_LENGTH ? 50L : 0L;

        profile.setCoins(profile.getCoins().add(BigInteger.valueOf(coins)));
        if (diamonds > 0) {
            profile.setDiamonds(profile.getDiamonds().add(BigInteger.valueOf(diamonds)));
        }
        if (credits > 0) {
            profile.setCredits(profile.getCredits().add(BigInteger.valueOf(credits)));
        }
        packs.getPlayerStore().save(player.getUniqueId());

        return new StreakResult(streak, dayInCycle, coins, diamonds, credits);
    }

    private int dayInCycle(int streak) {
        int mod = streak % CYCLE_LENGTH;
        return mod == 0 ? CYCLE_LENGTH : mod;
    }

    private long coinsFor(int dayInCycle) {
        return switch (dayInCycle) {
            case 1 -> 500L;
            case 2 -> 1_000L;
            case 3 -> 1_500L;
            case 4 -> 2_500L;
            case 5 -> 4_000L;
            case 6 -> 6_000L;
            case 7 -> 10_000L;
            default -> 0L;
        };
    }

    private long diamondsFor(int dayInCycle) {
        return switch (dayInCycle) {
            case 3 -> 5L;
            case 5 -> 10L;
            case 6 -> 5L;
            case 7 -> 25L;
            default -> 0L;
        };
    }
}
