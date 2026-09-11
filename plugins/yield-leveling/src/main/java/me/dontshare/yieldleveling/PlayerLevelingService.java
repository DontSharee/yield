package me.dontshare.yieldleveling;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldleveling.data.PlayerLevelingConfig;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Whole-player level/XP - separate from any individual pet's own level/XP
 * (see yield-packs' PetLevelingService). Only progress (level/xp) is ever
 * persisted, on {@link PackPlayerProfile} - {@link #xpForLevel} is always
 * computed fresh from the current config, so a mid-season curve rebalance
 * (editing player-leveling.yml, then /admin leveling reload) applies
 * instantly to every already-leveled player, same philosophy pet leveling
 * already established.
 * <p>
 * Rendered via the player's own real vanilla XP bar ({@link Player#setLevel}/
 * {@link Player#setExp}) rather than any custom UI - confirmed unused
 * anywhere else on this server.
 */
public final class PlayerLevelingService {

    private static final Duration FADE_IN = Duration.ofMillis(200);
    private static final Duration STAY = Duration.ofSeconds(2);
    private static final Duration FADE_OUT = Duration.ofMillis(500);

    private final Supplier<PlayerLevelingConfig> config;
    private final PlayerDataStore<PackPlayerProfile> store;
    /** Extra XP multiplier sources (e.g. yield-ranks' donor ranks) - multiplied together on top of the base 1.0, same keyed-registry shape as yield-packs' LuckService. */
    private final Map<String, Function<PackPlayerProfile, Double>> xpMultiplierProviders = new ConcurrentHashMap<>();

    public PlayerLevelingService(Supplier<PlayerLevelingConfig> config, PlayerDataStore<PackPlayerProfile> store) {
        this.config = config;
        this.store = store;
    }

    /** The current XP multiplier for this profile - the product of every currently-registered provider (1.0 if none are registered). */
    public double xpMultiplier(PackPlayerProfile profile) {
        double total = 1.0;
        for (Function<PackPlayerProfile, Double> provider : xpMultiplierProviders.values()) {
            total *= provider.apply(profile);
        }
        return total;
    }

    /** Registers (or replaces) this plugin's own keyed XP-multiplier contribution. */
    public void registerXpMultiplierProvider(String key, Function<PackPlayerProfile, Double> provider) {
        xpMultiplierProviders.put(key, provider);
    }

    /** Call on the registering plugin's onDisable. */
    public void unregisterXpMultiplierProvider(String key) {
        xpMultiplierProviders.remove(key);
    }

    /** How much XP {@code level} needs to reach {@code level + 1}. */
    public long xpForLevel(int level) {
        PlayerLevelingConfig cfg = config.get();
        return Math.round(cfg.curveBase() * Math.pow(level, cfg.curveExponent()));
    }

    /** Grants {@code amount} XP, rolling as many level-ups as it covers (capped at the configured max level, where XP simply stops accumulating further), saves, then re-syncs the vanilla bar. A no-op for a player with no cached profile yet. */
    public void grantXp(Player player, long amount) {
        if (amount <= 0) {
            return;
        }
        PackPlayerProfile profile = store.getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        amount = Math.round(amount * xpMultiplier(profile));
        int maxLevel = config.get().maxLevel();
        if (profile.getPlayerLevel() >= maxLevel) {
            return;
        }
        long xp = profile.getPlayerXp() + amount;
        int startLevel = profile.getPlayerLevel();
        int level = startLevel;
        while (level < maxLevel) {
            long needed = xpForLevel(level);
            if (xp < needed) {
                break;
            }
            xp -= needed;
            level++;
        }
        if (level >= maxLevel) {
            xp = 0;
        }
        profile.setPlayerLevel(level);
        profile.setPlayerXp(xp);
        store.save(player.getUniqueId());
        syncBar(player, profile);
        if (level > startLevel) {
            announceLevelUp(player, level);
        }
    }

    /** Previously completely silent - the vanilla XP bar ticking up was the ONLY feedback a level-up ever got, easy to miss entirely mid-combat. */
    private void announceLevelUp(Player player, int newLevel) {
        Component main = Text.parse("<#4BD9FF><bold>LEVEL UP!</bold></#4BD9FF>");
        Component sub = Text.parse("<gray>You are now level <level></gray>", Placeholder.unparsed("level", String.valueOf(newLevel)));
        player.showTitle(Title.title(main, sub, Title.Times.times(FADE_IN, STAY, FADE_OUT)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.05);
    }

    /** Pushes this profile's level/XP onto the player's real vanilla XP bar - call on join (the profile's own load is otherwise never reflected in the bar until the next XP grant) and after every {@link #grantXp}. */
    public void syncBar(Player player, PackPlayerProfile profile) {
        int maxLevel = config.get().maxLevel();
        player.setLevel(profile.getPlayerLevel());
        if (profile.getPlayerLevel() >= maxLevel) {
            player.setExp(1.0f);
            return;
        }
        long needed = xpForLevel(profile.getPlayerLevel());
        float ratio = needed <= 0 ? 0f : (float) Math.max(0.0, Math.min(0.999, profile.getPlayerXp() / (double) needed));
        player.setExp(ratio);
    }
}
