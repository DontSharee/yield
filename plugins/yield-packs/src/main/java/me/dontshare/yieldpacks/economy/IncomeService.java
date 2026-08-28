package me.dontshare.yieldpacks.economy;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pays out every online player's equipped-pet $/sec income once per second.
 * Mirrors YieldScoreboardDisplay's repeating-task shape. Online-only in v1 -
 * no offline-earnings catch-up.
 */
public final class IncomeService {

    private static final long TICK_INTERVAL = 20L; // 1 second

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Function<PackPlayerProfile, Double> coinMultiplierSupplier;

    public IncomeService(JavaPlugin plugin, PlayerDataStore<PackPlayerProfile> store, Supplier<ItemRegistry> itemRegistry,
                          Function<PackPlayerProfile, Double> coinMultiplierSupplier) {
        this.plugin = plugin;
        this.store = store;
        this.itemRegistry = itemRegistry;
        this.coinMultiplierSupplier = coinMultiplierSupplier;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /** The player's current $/sec from equipped pets, with the coin multiplier applied - also used by the scoreboard line. */
    public double currentPerSecond(PackPlayerProfile profile) {
        double perSecond = profile.getEquippedItemIds().stream()
                .map(itemRegistry.get()::find)
                .flatMap(Optional::stream)
                .mapToDouble(ItemDefinition::valuePerSecond)
                .sum();
        return perSecond * coinMultiplierSupplier.apply(profile);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PackPlayerProfile profile = store.getCached(player.getUniqueId());
            if (profile == null) {
                continue;
            }
            double perSecond = currentPerSecond(profile);
            if (perSecond > 0) {
                profile.setCoins(profile.getCoins() + Math.round(perSecond));
            }
        }
    }
}
