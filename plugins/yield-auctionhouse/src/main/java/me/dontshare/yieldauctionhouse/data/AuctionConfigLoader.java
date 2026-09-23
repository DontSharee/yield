package me.dontshare.yieldauctionhouse.data;

import me.dontshare.yieldcore.config.BundledConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class AuctionConfigLoader {

    private final JavaPlugin plugin;

    public AuctionConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public AuctionConfig load() {
        BundledConfig.sync(plugin, "auctionhouse.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "auctionhouse.yml"));

        double saleTaxPercent = config.getDouble("sale-tax-percent", 5.0);
        long listingDurationMillis = config.getLong("listing-duration-hours", 72) * 3_600_000L;
        long expirySweepIntervalTicks = config.getLong("expiry-sweep-interval-minutes", 5) * 20L * 60L;
        int maxActiveListingsPerPlayer = Math.max(1, config.getInt("max-active-listings-per-player", 10));
        long minimumPrice = Math.max(1, config.getLong("minimum-price", 1));
        long browseRefreshIntervalTicks = Math.max(20L, config.getLong("browse-refresh-interval-seconds", 3) * 20L);

        return new AuctionConfig(saleTaxPercent, listingDurationMillis, expirySweepIntervalTicks,
                maxActiveListingsPerPlayer, minimumPrice, browseRefreshIntervalTicks);
    }
}
