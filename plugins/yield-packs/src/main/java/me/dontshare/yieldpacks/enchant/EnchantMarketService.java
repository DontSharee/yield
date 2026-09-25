package me.dontshare.yieldpacks.enchant;

import me.dontshare.yieldcore.config.BundledConfig;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Enchant Market: a handful of Enchant Books per player, restocked for
 * everyone at the top of every real hour.
 * <p>
 * Nothing is stored to produce the offers. They are generated from the
 * player's UUID and the hour number, so a player sees the same six every
 * time they open the screen that hour, a different six from everyone
 * else, and a fresh six at :00 - with no per-player stock to save, no
 * restock job to run over every account, and nothing to go stale for a
 * player who was offline when the hour turned. The only saved state is
 * which of this hour's offers a player has already bought, and that is
 * discarded lazily the first time it is read in a later hour.
 * <p>
 * Prices are {@code basic cubes x the player's price basis} - the tier-1
 * coin value of the richest ladder zone they have unlocked, which
 * yield-zones supplies through {@link #setPriceBasis}. See
 * enchant-market.yml for why that and not eggs.
 */
public final class EnchantMarketService {

    public static final long RESTOCK_MILLIS = 60L * 60L * 1000L;
    /** How often the restock is looked for - the announcement can land up to this late, which nobody will notice. */
    private static final long WATCH_INTERVAL_TICKS = 20L * 10;

    /** One book on offer. {@code index} is its position in this hour's list - what a purchase is recorded against. */
    public record Offer(int index, EnchantType type, Rarity rarity, long priceInBasicCubes) {
    }

    public enum BuyResult { SUCCESS, SOLD_OUT, TOO_POOR, RESTOCKED }

    private record MarketConfig(int offers, Map<String, Double> rarityWeights, Map<String, Long> prices) {
    }

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<RarityRegistry> rarities;
    private final EnchantService enchantService;
    private volatile MarketConfig config;
    /** Tier-1 coin value to price against - replaced by yield-zones, which knows the zones. Ten coins is the Meadow's, the right answer for anyone with no zones. */
    private volatile Function<Player, BigInteger> priceBasis = player -> BigInteger.TEN;
    private long announcedHour;

    public EnchantMarketService(JavaPlugin plugin, PlayerDataStore<PackPlayerProfile> store,
                                 Supplier<RarityRegistry> rarities, EnchantService enchantService) {
        this.plugin = plugin;
        this.store = store;
        this.rarities = rarities;
        this.enchantService = enchantService;
        reload();
    }

    /** Discounts and surcharges on every offer, e.g. a blocktree perk - multiplied together, 1.0 = full price. */
    private final Map<String, Function<Player, Double>> priceMultiplierProviders = new java.util.concurrent.ConcurrentHashMap<>();

    public void registerPriceMultiplierProvider(String key, Function<Player, Double> provider) {
        priceMultiplierProviders.put(key, provider);
    }

    public void unregisterPriceMultiplierProvider(String key) {
        priceMultiplierProviders.remove(key);
    }

    public void setPriceBasis(Function<Player, BigInteger> priceBasis) {
        this.priceBasis = priceBasis;
    }

    public void reload() {
        BundledConfig.sync(plugin, "enchant-market.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "enchant-market.yml"));
        Map<String, Double> weights = new LinkedHashMap<>();
        ConfigurationSection weightSection = yaml.getConfigurationSection("rarity-weights");
        if (weightSection != null) {
            for (String id : weightSection.getKeys(false)) {
                if (rarities.get().find(id).isEmpty()) {
                    plugin.getLogger().warning("enchant-market.yml names unknown rarity '" + id + "' - skipping it.");
                    continue;
                }
                weights.put(id, Math.max(0.0, weightSection.getDouble(id)));
            }
        }
        Map<String, Long> prices = new LinkedHashMap<>();
        ConfigurationSection priceSection = yaml.getConfigurationSection("price-in-basic-cubes");
        for (String id : weights.keySet()) {
            long price = priceSection == null ? 0 : priceSection.getLong(id, 0);
            if (price <= 0) {
                // A rarity with no price would be free - better missing from
                // the market than handed out for nothing.
                plugin.getLogger().warning("enchant-market.yml has no price for rarity '" + id + "' - it will not be offered.");
                continue;
            }
            prices.put(id, price);
        }
        weights.keySet().retainAll(prices.keySet());
        config = new MarketConfig(Math.max(1, Math.min(7, yaml.getInt("offers", 6))), weights, prices);
    }

    public void start() {
        announcedHour = currentHour();
        Bukkit.getScheduler().runTaskTimer(plugin, me.dontshare.yieldcore.perf.PerfTracker.timed("enchantmarket.watch", this::watchForRestock), WATCH_INTERVAL_TICKS, WATCH_INTERVAL_TICKS);
    }

    public static long currentHour() {
        return System.currentTimeMillis() / RESTOCK_MILLIS;
    }

    public static long millisUntilRestock() {
        return RESTOCK_MILLIS - System.currentTimeMillis() % RESTOCK_MILLIS;
    }

    /** This player's offers for {@code hour}, cheapest rarity first - the same list every time for the same player and hour. */
    public List<Offer> offersFor(UUID playerId, long hour) {
        MarketConfig market = config;
        List<Map.Entry<Rarity, Double>> pool = new ArrayList<>();
        double total = 0;
        for (Map.Entry<String, Double> entry : market.rarityWeights().entrySet()) {
            Rarity rarity = rarities.get().find(entry.getKey()).orElse(null);
            if (rarity != null && entry.getValue() > 0) {
                pool.add(Map.entry(rarity, entry.getValue()));
                total += entry.getValue();
            }
        }
        if (pool.isEmpty()) {
            return List.of();
        }
        SplittableRandom random = new SplittableRandom(seedFor(playerId, hour));
        EnchantType[] types = EnchantType.values();
        List<Offer> rolled = new ArrayList<>();
        for (int i = 0; i < market.offers(); i++) {
            EnchantType type = types[random.nextInt(types.length)];
            double pick = random.nextDouble() * total;
            Rarity chosen = pool.get(pool.size() - 1).getKey();
            for (Map.Entry<Rarity, Double> entry : pool) {
                pick -= entry.getValue();
                if (pick < 0) {
                    chosen = entry.getKey();
                    break;
                }
            }
            rolled.add(new Offer(0, type, chosen, market.prices().get(chosen.id())));
        }
        rolled.sort(Comparator.comparingInt((Offer offer) -> offer.rarity().sortOrder())
                .thenComparing(offer -> offer.type().ordinal()));
        List<Offer> offers = new ArrayList<>(rolled.size());
        for (int i = 0; i < rolled.size(); i++) {
            Offer offer = rolled.get(i);
            offers.add(new Offer(i, offer.type(), offer.rarity(), offer.priceInBasicCubes()));
        }
        return offers;
    }

    public BigInteger priceOf(Player player, Offer offer) {
        BigInteger full = priceBasis.apply(player).max(BigInteger.ONE).multiply(BigInteger.valueOf(offer.priceInBasicCubes()));
        double factor = 1.0;
        for (Function<Player, Double> provider : priceMultiplierProviders.values()) {
            Double value = provider.apply(player);
            if (value != null) {
                factor *= Math.max(0.0, value);
            }
        }
        if (factor == 1.0) {
            return full;
        }
        // Basis points keep the maths in BigInteger - a price can outgrow a double's exact range.
        long basisPoints = Math.max(1L, Math.round(factor * 10_000));
        return full.multiply(BigInteger.valueOf(basisPoints)).divide(BigInteger.valueOf(10_000)).max(BigInteger.ONE);
    }

    public boolean isBought(PackPlayerProfile profile, long hour, int index) {
        return profile.getEnchantMarketHour() == hour && profile.getEnchantMarketBought().contains(index);
    }

    /**
     * Buys offer {@code index} of {@code hour}. The hour is passed in
     * rather than read here so a click on a screen opened at 12:59 and
     * clicked at 13:00 is refused, not quietly applied to a different
     * book: the offer the player was looking at no longer exists.
     * <p>
     * The coins, the "bought" mark and the save all happen before the book
     * is handed over, and every click runs on the main thread, so a
     * double-click finds the offer already sold.
     */
    public BuyResult buy(Player player, long hour, int index) {
        if (hour != currentHour()) {
            return BuyResult.RESTOCKED;
        }
        List<Offer> offers = offersFor(player.getUniqueId(), hour);
        if (index < 0 || index >= offers.size()) {
            return BuyResult.RESTOCKED;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        if (isBought(profile, hour, index)) {
            return BuyResult.SOLD_OUT;
        }
        Offer offer = offers.get(index);
        BigInteger price = priceOf(player, offer);
        if (profile.getCoins().compareTo(price) < 0) {
            return BuyResult.TOO_POOR;
        }
        profile.setCoins(profile.getCoins().subtract(price));
        if (profile.getEnchantMarketHour() != hour) {
            profile.setEnchantMarketHour(hour);
            profile.getEnchantMarketBought().clear();
        }
        profile.getEnchantMarketBought().add(index);
        store.save(player.getUniqueId());
        enchantService.giveBook(player, offer.type(), offer.rarity());
        return BuyResult.SUCCESS;
    }

    /** Whether any of this player's offers this hour is Legendary or better. */
    public boolean hasLegendary(UUID playerId, long hour) {
        return offersFor(playerId, hour).stream()
                .anyMatch(offer -> offer.rarity().sortOrder() >= EnchantService.LEGENDARY_SORT);
    }

    /**
     * One line to everyone at the restock, and a louder one to each player
     * whose own market rolled a Legendary - the whole reason to come and
     * look, said at the one moment it is new.
     */
    private void watchForRestock() {
        long hour = currentHour();
        if (hour == announcedHour) {
            return;
        }
        announcedHour = hour;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hasLegendary(player.getUniqueId(), hour)) {
                player.sendMessage(Text.parse("<#4BD9FF>✦</#4BD9FF> <gold><bold>Your Enchant Market has a LEGENDARY book"
                        + " this hour!</bold></gold> <gray>/enchantmarket</gray>"));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
            } else {
                player.sendMessage(Text.parse("<#4BD9FF>✦</#4BD9FF> <gray>The Enchant Market has restocked.</gray>"
                        + " <dark_gray>/enchantmarket</dark_gray>"));
            }
        }
    }

    /** Spreads the UUID and hour over all 64 bits (a SplitMix64 finaliser) so neighbouring hours and similar UUIDs don't roll similar markets. */
    private static long seedFor(UUID playerId, long hour) {
        long h = playerId.getMostSignificantBits() ^ Long.rotateLeft(playerId.getLeastSignificantBits(), 17)
                ^ (hour * 0x9E3779B97F4A7C15L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
