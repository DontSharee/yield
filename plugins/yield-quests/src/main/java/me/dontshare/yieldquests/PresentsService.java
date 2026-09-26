package me.dontshare.yieldquests;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldquests.data.PresentDefinition;
import me.dontshare.yieldquests.data.PresentsContentLoader.PresentsContent;
import me.dontshare.yieldquests.data.QuestProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The /daily gifts: a chain of presents that unlock with the time a player
 * has played TODAY, across any number of sessions, and reset at midnight
 * (server time, the same moment daily quests do). Opened gifts stay opened
 * until then - relogging neither re-locks what's unlocked nor lets anything
 * be opened twice.
 * <p>
 * Playtime is added to the player's saved record every few seconds while
 * they play, so a quit or a crash loses at most that much.
 */
public final class PresentsService {

    public enum ClaimResult { SUCCESS, LOCKED, ALREADY_CLAIMED, INVALID }

    private final Supplier<PresentsContent> content;
    private final YieldPacks packs;
    private final PlayerDataStore<QuestProfile> store;
    /** Up to when each online player's playtime has been added to their record. */
    private final Map<UUID, Long> countedUntil = new ConcurrentHashMap<>();

    public PresentsService(Supplier<PresentsContent> content, YieldPacks packs, PlayerDataStore<QuestProfile> store) {
        this.content = content;
        this.packs = packs;
        this.store = store;
    }

    public void onJoin(Player player) {
        countedUntil.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public void onQuit(Player player) {
        bank(player.getUniqueId());
        countedUntil.remove(player.getUniqueId());
    }

    /** Main thread, every few seconds: adds everyone's playtime since last time to their record. */
    public void bankAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            bank(player.getUniqueId());
        }
    }

    private void bank(UUID id) {
        QuestProfile profile = store.getCached(id);
        Long from = countedUntil.get(id);
        if (profile == null || from == null) {
            return;
        }
        rollover(profile);
        long now = System.currentTimeMillis();
        long start = Math.max(from, todayStartMillis());
        if (now > start) {
            profile.setGiftPlayMs(profile.getGiftPlayMs() + (now - start));
        }
        countedUntil.put(id, now);
    }

    /** A new day: yesterday's playtime and opened gifts no longer count. */
    private static void rollover(QuestProfile profile) {
        long today = LocalDate.now().toEpochDay();
        if (profile.getGiftEpochDay() != today) {
            profile.setGiftEpochDay(today);
            profile.setGiftPlayMs(0);
            profile.setGiftsClaimed(new HashSet<>());
        }
    }

    private static long todayStartMillis() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** How long until every gift resets - the next midnight, server time. */
    public long millisUntilReset() {
        return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - System.currentTimeMillis();
    }

    /** Time played today, this session included. */
    public long playedTodayMillis(Player player) {
        QuestProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return 0;
        }
        rollover(profile);
        Long from = countedUntil.get(player.getUniqueId());
        long unbanked = from == null ? 0 : Math.max(0, System.currentTimeMillis() - Math.max(from, todayStartMillis()));
        return profile.getGiftPlayMs() + unbanked;
    }

    public List<PresentDefinition> presents() {
        return content.get().presents();
    }

    public boolean isUnlocked(Player player, int index) {
        List<PresentDefinition> presents = presents();
        return index >= 0 && index < presents.size()
                && playedTodayMillis(player) >= presents.get(index).unlockAfterMinutes() * 60_000L;
    }

    public boolean isClaimed(Player player, int index) {
        QuestProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return false;
        }
        rollover(profile);
        return profile.getGiftsClaimed().contains(index);
    }

    private void markClaimed(Player player, int index) {
        QuestProfile profile = store.getOrCreate(player.getUniqueId());
        rollover(profile);
        profile.getGiftsClaimed().add(index);
        store.save(player.getUniqueId());
    }

    public ClaimResult claim(Player player, int index) {
        List<PresentDefinition> presents = presents();
        if (index < 0 || index >= presents.size()) {
            return ClaimResult.INVALID;
        }
        if (isClaimed(player, index)) {
            return ClaimResult.ALREADY_CLAIMED;
        }
        if (!isUnlocked(player, index)) {
            return ClaimResult.LOCKED;
        }
        PresentDefinition present = presents.get(index);
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        long coins = Math.round(present.coins() * packs.coinMultiplier(profile));
        profile.setCoins(profile.getCoins().add(BigInteger.valueOf(coins)));
        profile.setDiamonds(profile.getDiamonds().add(BigInteger.valueOf(present.diamonds())));
        packs.getPlayerStore().save(player.getUniqueId());
        markClaimed(player, index);
        return ClaimResult.SUCCESS;
    }

    /** What opening a present pays - coins already scaled by the player's coin multiplier. */
    public record Reward(long coins, long diamonds) {
    }

    /**
     * Marks a present claimed and hands back what it pays, WITHOUT crediting
     * it - the physical present sprays it out as loot that credits itself
     * as it's collected (see GiftDisplayService). Null when it can't be
     * claimed (locked, already claimed, no such present).
     */
    public Reward claimForDrop(Player player, int index) {
        List<PresentDefinition> presents = presents();
        if (index < 0 || index >= presents.size() || isClaimed(player, index) || !isUnlocked(player, index)) {
            return null;
        }
        PresentDefinition present = presents.get(index);
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        markClaimed(player, index);
        return new Reward(Math.round(present.coins() * packs.coinMultiplier(profile)), present.diamonds());
    }

    /** The first present that's unlocked and not yet opened, or -1. */
    public int nextOpenable(Player player) {
        List<PresentDefinition> presents = presents();
        for (int i = 0; i < presents.size(); i++) {
            if (isUnlocked(player, i) && !isClaimed(player, i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The sidebar's gift line: 0 if a present is ready to open now, the
     * milliseconds until the next one unlocks otherwise, or -1 once every
     * present today is opened.
     */
    public long millisUntilNextGift(Player player) {
        if (!countedUntil.containsKey(player.getUniqueId())) {
            return -1;
        }
        long played = playedTodayMillis(player);
        long soonest = -1;
        List<PresentDefinition> presents = presents();
        for (int i = 0; i < presents.size(); i++) {
            if (isClaimed(player, i)) {
                continue;
            }
            long wait = Math.max(0, presents.get(i).unlockAfterMinutes() * 60_000L - played);
            if (soonest < 0 || wait < soonest) {
                soonest = wait;
            }
        }
        return soonest;
    }

    /** Milliseconds of play still needed to unlock this present - 0 once it's unlocked. */
    public long millisUntilUnlock(Player player, int index) {
        List<PresentDefinition> presents = presents();
        if (index < 0 || index >= presents.size()) {
            return 0;
        }
        return Math.max(0, presents.get(index).unlockAfterMinutes() * 60_000L - playedTodayMillis(player));
    }
}
