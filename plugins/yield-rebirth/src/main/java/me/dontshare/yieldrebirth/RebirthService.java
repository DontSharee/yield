package me.dontshare.yieldrebirth;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldrebirth.event.RebirthEvent;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.function.Function;

/**
 * Rebirth cost curve and affordability math. Rebirthing only deducts the
 * cost of the rebirth(s) actually taken from the player's coins - it never
 * resets the balance to zero, and never touches the bag or equipped pets.
 */
public final class RebirthService {

    /**
     * A single {@link #preview} call is capped at this many rebirths - coins
     * are now unbounded (BigInteger), and a misconfigured growth rate at or
     * below 1.0 (cost never actually rises) against a huge balance would
     * otherwise loop essentially forever computing an astronomical
     * {@code available} count in one synchronous call.
     */
    private static final int MAX_REBIRTHS_PER_PREVIEW = 100_000;

    public record RebirthPreview(int available, BigInteger totalCost) {
    }

    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final double baseCost;
    private final double growth;
    private final double coinBonusPerRebirth;
    /** How many EFFECTIVE rebirths one actual rebirth actually grants - e.g. yield-upgrades' rebirth-boost upgrade. Owned by {@link YieldRebirth} (not here) so it survives {@code reloadRebirthConfig} replacing this instance wholesale - same reasoning coinMultiplier's own registry lives on YieldPacks rather than inside whatever reads it. Defaults to a flat 1.0 (today's plain behavior) if the caller doesn't wire anything fancier in. */
    private final Function<PackPlayerProfile, Double> grantMultiplier;

    public RebirthService(PlayerDataStore<PackPlayerProfile> playerStore, FileConfiguration config,
                           Function<PackPlayerProfile, Double> grantMultiplier) {
        this.playerStore = playerStore;
        this.baseCost = config.getDouble("base-cost", 250_000);
        this.growth = config.getDouble("growth", 1.12);
        this.coinBonusPerRebirth = config.getDouble("coin-bonus-per-rebirth", 0.05);
        this.grantMultiplier = grantMultiplier;
    }

    /**
     * {@code baseCost * growth^rebirthNumber}, computed via BigDecimal
     * rather than {@code Math.pow}/{@code Math.round} - the latter overflows
     * to {@code Infinity} (then clamps silently instead of throwing) around
     * rebirth #1780 at the default growth rate, well within reach now that
     * coin balances are themselves unbounded.
     */
    public BigInteger requiredCoins(int rebirthNumber) {
        BigDecimal cost = BigDecimal.valueOf(baseCost).multiply(BigDecimal.valueOf(growth).pow(rebirthNumber));
        return cost.setScale(0, RoundingMode.HALF_UP).toBigInteger();
    }

    /** The permanent coin-income multiplier from rebirths - see rebirth.yml's {@code coin-bonus-per-rebirth}. */
    public double coinMultiplier(PackPlayerProfile profile) {
        return coinMultiplierForRebirths(profile.getRebirths());
    }

    /** Same formula as {@link #coinMultiplier}, for an arbitrary rebirth count rather than a real profile - lets a preview (e.g. RebirthDialog's "boost after") compute a hypothetical without needing a fake profile. */
    public double coinMultiplierForRebirths(int rebirths) {
        return 1.0 + rebirths * coinBonusPerRebirth;
    }

    /** How many rebirths the player could take right now, and their combined cost, without applying anything. */
    public RebirthPreview preview(PackPlayerProfile profile) {
        int available = 0;
        BigInteger totalCost = BigInteger.ZERO;
        BigInteger remaining = profile.getCoins();
        while (available < MAX_REBIRTHS_PER_PREVIEW) {
            BigInteger nextCost = requiredCoins(profile.getRebirths() + available);
            if (nextCost.compareTo(remaining) > 0) {
                break;
            }
            remaining = remaining.subtract(nextCost);
            totalCost = totalCost.add(nextCost);
            available++;
        }
        return new RebirthPreview(available, totalCost);
    }

    /**
     * Deducts only the cost of the rebirth(s) taken - never a full balance
     * reset. The actual rebirths GRANTED can exceed the base
     * {@code preview.available()} count (e.g. an upgrade-boosted player
     * taking 1 rebirth's worth of coins might gain 1.2, rounded) - cost is
     * always based on the un-boosted count, only the payout is scaled.
     * Returns the (un-boosted) preview that was paid for.
     */
    public RebirthPreview rebirth(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        RebirthPreview preview = preview(profile);
        if (preview.available() > 0) {
            int granted = (int) Math.round(preview.available() * grantMultiplier.apply(profile));
            profile.setCoins(profile.getCoins().subtract(preview.totalCost()));
            profile.setRebirths(profile.getRebirths() + granted);
            playerStore.save(player.getUniqueId());
            Bukkit.getPluginManager().callEvent(new RebirthEvent(player, granted, profile.getRebirths()));
        }
        return preview;
    }
}
