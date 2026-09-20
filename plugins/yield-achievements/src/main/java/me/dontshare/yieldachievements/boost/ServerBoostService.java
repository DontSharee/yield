package me.dontshare.yieldachievements.boost;

import me.dontshare.yieldachievements.potion.PotionStat;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide boosts - the thing that makes a server feel inhabited rather
 * than like a lot of people playing single-player next to each other.
 * Everyone online shares one multiplier, everyone sees the same boss bar
 * draining, and chat marks the start and the end.
 * <p>
 * Mechanically this is the global twin of {@code PotionService}: same
 * {@link PotionStat} set, same "extend if identical, stack if not" rule,
 * and it plugs into the exact same multiplier registries on {@code
 * YieldPacks}. The only real differences are that the state is one shared
 * map rather than per-player, and that it is loud.
 * <p>
 * One boss bar per active boost, so two concurrent boosts read as two bars
 * rather than one line trying to describe both. Titles and progress are
 * rewritten once a second by {@link #start}'s ticker; Adventure pushes each
 * change to every viewer, so the countdown and the drain are the same
 * update.
 */
public final class ServerBoostService {

    /** One second. The countdown is per-second by design - a bar that moves in visible steps reads as live, one that creeps does not. */
    private static final long TICK_INTERVAL_TICKS = 20L;

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final ServerBoostStore store;

    /** Keyed by {@link ServerBoost#key()} - the same key that decides whether a new boost extends an existing one. */
    private final Map<String, ServerBoost> active = new ConcurrentHashMap<>();
    private final Map<String, BossBar> barsByKey = new ConcurrentHashMap<>();

    public ServerBoostService(JavaPlugin plugin, DatabaseManager databaseManager, ServerBoostStore store) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.store = store;
    }

    /** Restores anything that outlived a restart, then starts the once-a-second ticker. */
    public void start() {
        databaseManager.supplyAsync(store::loadActive).thenAccept(restored -> Bukkit.getScheduler().runTask(plugin, () -> {
            for (ServerBoost boost : restored) {
                active.put(boost.key(), boost);
            }
            if (!restored.isEmpty()) {
                plugin.getLogger().info("Restored " + restored.size() + " server boost(s) still running from before the restart.");
            }
        }));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);
    }

    /**
     * Starts a boost, or extends an already-running one of the exact same
     * stat and strength. Announced either way - a player who was not online
     * for the original start still wants to know one is running.
     *
     * @param startedBy who or what triggered it, purely for the message
     */
    public void startBoost(PotionStat stat, double multiplier, long durationSeconds, String startedBy) {
        if (multiplier <= 0 || durationSeconds <= 0) {
            return;
        }
        String key = stat.name() + "_" + multiplier;
        ServerBoost existing = active.get(key);
        long now = System.currentTimeMillis();
        // Extending stacks TIME, never strength - two "2x Coins" running at
        // once would silently be 4x, which is not what anyone starting a
        // second one intends.
        long base = existing != null && !existing.expired() ? existing.endsAtMillis() : now;
        long totalSeconds = (base - now) / 1000 + durationSeconds;
        boolean extending = existing != null && !existing.expired();
        ServerBoost boost = new ServerBoost(stat, multiplier, base + durationSeconds * 1000L, totalSeconds);
        active.put(key, boost);
        databaseManager.supplyAsync(() -> {
            store.save(boost);
            return null;
        });

        // Extending says the new TOTAL, not the slice just added - "2x Coins
        // for 30 minutes" when there were already five minutes on the clock
        // is a number nobody can act on.
        Bukkit.broadcast(Text.parse(extending
                        ? "<gradient:#FFD700:#FFA500><bold>⚡ BOOST EXTENDED ⚡</bold></gradient> "
                            + "<white><multiplier> <stat></white> <gray>- now</gray> <white><duration></white> "
                            + "<gray>remaining!</gray> <dark_gray>(<gray><by></gray>)</dark_gray>"
                        : "<gradient:#FFD700:#FFA500><bold>⚡ SERVER BOOST ⚡</bold></gradient> "
                            + "<white><multiplier> <stat></white> <gray>for</gray> <white><duration></white>"
                            + "<gray>!</gray> <dark_gray>(<gray><by></gray>)</dark_gray>",
                Placeholder.unparsed("multiplier", boost.multiplierLabel()),
                Placeholder.unparsed("stat", labelFor(stat)),
                Placeholder.unparsed("duration", describeDuration(extending ? boost.remainingSeconds() : durationSeconds)),
                Placeholder.unparsed("by", startedBy)));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        }
        tick();
    }

    /** Ends a boost early. Returns false if nothing by that key was running. */
    public boolean stopBoost(PotionStat stat, double multiplier) {
        ServerBoost boost = active.get(stat.name() + "_" + multiplier);
        if (boost == null) {
            return false;
        }
        end(boost);
        return true;
    }

    /** The product of every live boost on {@code stat} - 1.0 when none is running. */
    public double multiplierFor(PotionStat stat) {
        double total = 1.0;
        for (ServerBoost boost : active.values()) {
            if (boost.stat() == stat && !boost.expired()) {
                total *= boost.multiplier();
            }
        }
        return total;
    }

    /** Every live boost, longest-remaining first - for an admin listing. */
    public List<ServerBoost> activeBoosts() {
        List<ServerBoost> boosts = new ArrayList<>();
        for (ServerBoost boost : active.values()) {
            if (!boost.expired()) {
                boosts.add(boost);
            }
        }
        boosts.sort((a, b) -> Long.compare(b.remainingSeconds(), a.remainingSeconds()));
        return boosts;
    }

    // ------------------------------------------------------------- internals

    private void tick() {
        for (ServerBoost boost : List.copyOf(active.values())) {
            if (boost.expired()) {
                end(boost);
                continue;
            }
            BossBar bar = barsByKey.computeIfAbsent(boost.key(), key ->
                    BossBar.bossBar(titleFor(boost), boost.progress(), colorFor(boost.stat()), BossBar.Overlay.PROGRESS));
            bar.name(titleFor(boost));
            bar.progress(boost.progress());
            // Re-shown every tick rather than only on join: a player who
            // logged in mid-boost, respawned, or changed worlds still needs
            // the bar, and Adventure treats showing an already-shown bar as
            // a no-op on its own viewer set.
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.showBossBar(bar);
            }
        }
    }

    private void end(ServerBoost boost) {
        active.remove(boost.key());
        BossBar bar = barsByKey.remove(boost.key());
        if (bar != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.hideBossBar(bar);
            }
        }
        databaseManager.supplyAsync(() -> {
            store.delete(boost.key());
            return null;
        });
        Bukkit.broadcast(Text.parse(
                "<gray>The</gray> <white><multiplier> <stat></white> <gray>server boost has ended.</gray>",
                Placeholder.unparsed("multiplier", boost.multiplierLabel()),
                Placeholder.unparsed("stat", labelFor(boost.stat()))));
    }

    /** Called from onDisable - takes the bars off every screen without ending the boosts themselves, which are persisted and resume on the next start. */
    public void shutdown() {
        for (BossBar bar : barsByKey.values()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.hideBossBar(bar);
            }
        }
        barsByKey.clear();
    }

    private net.kyori.adventure.text.Component titleFor(ServerBoost boost) {
        return Text.parse("<gradient:#FFD700:#FFA500><bold>⚡ <multiplier> <stat></bold></gradient> <dark_gray>|</dark_gray> <white><time></white>",
                Placeholder.unparsed("multiplier", boost.multiplierLabel()),
                Placeholder.unparsed("stat", labelFor(boost.stat()).toUpperCase(Locale.ROOT)),
                Placeholder.unparsed("time", formatClock(boost.remainingSeconds())));
    }

    /** One color per stat so two concurrent bars are told apart at a glance rather than by reading them. */
    private BossBar.Color colorFor(PotionStat stat) {
        return switch (stat) {
            case COINS -> BossBar.Color.YELLOW;
            case DAMAGE -> BossBar.Color.RED;
            case LUCK -> BossBar.Color.GREEN;
            case ROLL_SPEED -> BossBar.Color.BLUE;
        };
    }

    private String labelFor(PotionStat stat) {
        return switch (stat) {
            case COINS -> "Coins";
            case DAMAGE -> "Damage";
            case LUCK -> "Luck";
            case ROLL_SPEED -> "Roll Speed";
        };
    }

    /** "4:59" / "1:04:59" - the countdown on the bar, where every second has to be readable at a glance. */
    private String formatClock(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /** "30 minutes" / "90 seconds" - the chat announcement, where a phrase reads better than a clock. */
    private String describeDuration(long totalSeconds) {
        if (totalSeconds % 3600 == 0 && totalSeconds >= 3600) {
            long hours = totalSeconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        if (totalSeconds % 60 == 0 && totalSeconds >= 60) {
            long minutes = totalSeconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return totalSeconds + (totalSeconds == 1 ? " second" : " seconds");
    }
}
