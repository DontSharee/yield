package me.dontshare.yieldpacks.storage;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * How many pets a player's Bag holds.
 * <p>
 * Everyone starts at {@link #DEFAULT_CAPACITY}. Diamonds buy it up in
 * steps of {@link #SLOTS_PER_UPGRADE} (the play-to-win route), each step
 * doubling in price so it keeps pace with diamond income, which grows about
 * 2.3x a zone; the Infinite Storage pass ({@link #INFINITE_PERMISSION})
 * removes the limit. Other plugins can add flat room through
 * {@link #registerBonusCapacityProvider} (a rank perk, say).
 * <p>
 * The limit is enforced where a player CHOOSES to take in more pets -
 * hatching, auto-hatch, opening a crate or lootbox, redeeming a pet item -
 * so nothing is ever lost to a full bag: the action is refused before
 * anything is spent. Rewards handed out by the server (quests, admin
 * commands, fusion results) may still take a player over the limit; they
 * just can't hatch more until they make room.
 */
public final class BagStorageService {

    public static final int DEFAULT_CAPACITY = 300;
    public static final int SLOTS_PER_UPGRADE = 50;
    public static final int MAX_UPGRADES = 20;
    /** The first upgrade's price; each one after costs twice the last. */
    public static final long BASE_UPGRADE_COST_DIAMONDS = 25L;
    public static final String INFINITE_PERMISSION = "yieldpacks.storage.infinite";

    public enum UpgradeResult { SUCCESS, MAXED, INFINITE, TOO_POOR }

    private final PlayerDataStore<PackPlayerProfile> store;
    private final Map<String, Function<PackPlayerProfile, Integer>> bonusProviders = new ConcurrentHashMap<>();

    public BagStorageService(PlayerDataStore<PackPlayerProfile> store) {
        this.store = store;
    }

    public void registerBonusCapacityProvider(String key, Function<PackPlayerProfile, Integer> provider) {
        bonusProviders.put(key, provider);
    }

    public void unregisterBonusCapacityProvider(String key) {
        bonusProviders.remove(key);
    }

    public boolean isInfinite(Player player) {
        return player.hasPermission(INFINITE_PERMISSION);
    }

    /** How many pets this player can hold - {@link Integer#MAX_VALUE} with Infinite Storage. */
    public int capacity(Player player, PackPlayerProfile profile) {
        if (isInfinite(player)) {
            return Integer.MAX_VALUE;
        }
        int bonus = 0;
        for (Function<PackPlayerProfile, Integer> provider : bonusProviders.values()) {
            Integer value = provider.apply(profile);
            if (value != null) {
                bonus += Math.max(0, value);
            }
        }
        return DEFAULT_CAPACITY + profile.getStorageUpgrades() * SLOTS_PER_UPGRADE + bonus;
    }

    public int used(PackPlayerProfile profile) {
        return profile.getPets().size();
    }

    /** Whether {@code count} more pets fit right now. */
    public boolean hasRoom(Player player, PackPlayerProfile profile, int count) {
        return isInfinite(player) || (long) used(profile) + count <= capacity(player, profile);
    }

    /** "Your Bag is full (300/300)..." - what every refused action says. */
    public String fullMessage(Player player, PackPlayerProfile profile) {
        return "Your Bag is full (" + Formatting.format((double) used(profile)) + "/"
                + Formatting.format((double) capacity(player, profile))
                + ") - delete or fuse pets, or buy more storage in your Bag.";
    }

    /** "123/300", or "123/∞". */
    public String usageLabel(Player player, PackPlayerProfile profile) {
        return Formatting.format((double) used(profile)) + "/"
                + (isInfinite(player) ? "∞" : Formatting.format((double) capacity(player, profile)));
    }

    /** The next upgrade's price, or null when there isn't one. */
    public BigInteger nextUpgradeCost(PackPlayerProfile profile) {
        int level = profile.getStorageUpgrades();
        if (level >= MAX_UPGRADES) {
            return null;
        }
        return BigInteger.valueOf(BASE_UPGRADE_COST_DIAMONDS).shiftLeft(level);
    }

    public UpgradeResult buyUpgrade(Player player) {
        if (isInfinite(player)) {
            return UpgradeResult.INFINITE;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        BigInteger cost = nextUpgradeCost(profile);
        if (cost == null) {
            return UpgradeResult.MAXED;
        }
        if (profile.getDiamonds().compareTo(cost) < 0) {
            return UpgradeResult.TOO_POOR;
        }
        profile.setDiamonds(profile.getDiamonds().subtract(cost));
        profile.setStorageUpgrades(profile.getStorageUpgrades() + 1);
        store.save(player.getUniqueId());
        return UpgradeResult.SUCCESS;
    }
}
