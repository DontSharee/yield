package me.dontshare.yieldquests;

import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldquests.data.PresentDefinition;
import me.dontshare.yieldquests.data.PresentsContentLoader.PresentsContent;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The /daily present chain - purely in-memory, tied to THIS play session
 * (join to quit), never persisted. A present unlocks once the player has
 * been online long enough this session; once unlocked it stays claimable
 * for the rest of the session in any order - only disconnecting resets
 * everything back to locked/unclaimed, which is the entire point (rewards
 * a longer session, not just repeatedly logging in and out).
 */
public final class PresentsService {

    public enum ClaimResult { SUCCESS, LOCKED, ALREADY_CLAIMED, INVALID }

    private final Supplier<PresentsContent> content;
    private final YieldPacks packs;
    private final Map<UUID, Long> joinedAtMillis = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> claimedThisSession = new ConcurrentHashMap<>();

    public PresentsService(Supplier<PresentsContent> content, YieldPacks packs) {
        this.content = content;
        this.packs = packs;
    }

    public void onJoin(Player player) {
        joinedAtMillis.put(player.getUniqueId(), System.currentTimeMillis());
        claimedThisSession.put(player.getUniqueId(), ConcurrentHashMap.newKeySet());
    }

    public void onQuit(Player player) {
        joinedAtMillis.remove(player.getUniqueId());
        claimedThisSession.remove(player.getUniqueId());
    }

    public long onlineMinutes(Player player) {
        Long joinedAt = joinedAtMillis.get(player.getUniqueId());
        return joinedAt == null ? 0 : (System.currentTimeMillis() - joinedAt) / 60_000L;
    }

    public List<PresentDefinition> presents() {
        return content.get().presents();
    }

    public boolean isUnlocked(Player player, int index) {
        List<PresentDefinition> presents = presents();
        return index >= 0 && index < presents.size() && onlineMinutes(player) >= presents.get(index).unlockAfterMinutes();
    }

    public boolean isClaimed(Player player, int index) {
        return claimedThisSession.getOrDefault(player.getUniqueId(), Set.of()).contains(index);
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
        claimedThisSession.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet()).add(index);
        return ClaimResult.SUCCESS;
    }

    /** Minutes remaining until this present unlocks - 0 if already unlocked. */
    public long minutesUntilUnlock(Player player, int index) {
        List<PresentDefinition> presents = presents();
        if (index < 0 || index >= presents.size()) {
            return 0;
        }
        return Math.max(0, presents.get(index).unlockAfterMinutes() - onlineMinutes(player));
    }
}
