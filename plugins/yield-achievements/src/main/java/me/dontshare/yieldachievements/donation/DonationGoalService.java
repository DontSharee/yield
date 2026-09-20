package me.dontshare.yieldachievements.donation;

import me.dontshare.yieldachievements.donation.DonationGoalStore.Progress;
import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The community donation goal: every $100 the server takes permanently adds
 * {@code +0.1} to a server-wide COIN multiplier that everyone gets forever,
 * and the counter resets to chase the next $100.
 * <p>
 * Coins on purpose, and only coins. Coins are the one stat in this game with
 * no ceiling - the combat model floors kill time at one second, so a
 * permanent unbounded DAMAGE multiplier would eventually make every cube
 * one-shot and flatten combat, and a permanent LUCK multiplier would inflate
 * Huge/Secret odds forever and cheapen the chase. A permanent coin
 * multiplier just moves everyone along the same curve faster, which is
 * exactly what a community goal should do. See BALANCE.md.
 * <p>
 * The bar is always on screen - it is a standing invitation, not an event -
 * so unlike {@code ServerBoostService}'s bars it is never hidden while the
 * server is up.
 */
public final class DonationGoalService {

    private static final long TICK_INTERVAL_TICKS = 20L;

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final DonationGoalStore store;

    /** Replaced wholesale rather than mutated, so a reader never sees a half-applied contribution. */
    private final AtomicReference<Progress> progress = new AtomicReference<>(Progress.EMPTY);
    private volatile BossBar bar;
    /** What the bar's title was last built from, so it is only rebuilt when the number actually moved. */
    private volatile String renderedTitleKey = "";

    public DonationGoalService(JavaPlugin plugin, DatabaseManager databaseManager, DonationGoalStore store) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.store = store;
    }

    public void start() {
        databaseManager.supplyAsync(store::load).thenAccept(loaded ->
                Bukkit.getScheduler().runTask(plugin, () -> progress.set(loaded)));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);
    }

    /** The permanent server-wide coin multiplier bought by donations so far - 1.0 before the first goal. */
    public double coinMultiplier() {
        return progress.get().permanentMultiplier();
    }

    public Progress progress() {
        return progress.get();
    }

    /**
     * Records a purchase against the goal, completing as many $100 goals as
     * it spans (a single large donation can cross more than one) and
     * announcing each.
     */
    public void recordPurchase(long credits) {
        if (credits <= 0) {
            return;
        }
        Progress before = progress.get();
        long banked = before.creditsTowardGoal() + credits;
        long newGoals = banked / DonationGoalStore.CREDITS_PER_GOAL;
        // The remainder carries, so nothing donated is ever rounded away -
        // "reset the counter" means reset past the goal, not to zero.
        long remainder = banked % DonationGoalStore.CREDITS_PER_GOAL;
        Progress after = new Progress(remainder, before.goalsCompleted() + newGoals,
                before.lifetimeCredits() + credits);
        progress.set(after);
        databaseManager.supplyAsync(() -> {
            store.save(after);
            return null;
        });
        for (long i = 0; i < newGoals; i++) {
            announceGoalReached(before.goalsCompleted() + i + 1);
        }
        tick();
    }

    private void announceGoalReached(long goalNumber) {
        double multiplier = 1.0 + goalNumber * DonationGoalStore.MULTIPLIER_PER_GOAL;
        Bukkit.broadcast(Text.parse(
                "<gradient:#FFD700:#FF8C00><bold>★ DONATION GOAL REACHED ★</bold></gradient>"));
        Bukkit.broadcast(Text.parse(
                "<gray>The server has hit</gray> <white>$<total></white><gray>! Everyone now earns</gray> "
                        + "<gold><bold><multiplier>x coins</bold></gold> <gray>- permanently.</gray>",
                Placeholder.unparsed("total", String.valueOf(goalNumber * 100)),
                Placeholder.unparsed("multiplier", trim(multiplier))));
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        }
    }

    /** Announces a purchase itself - separate from the goal message, since most purchases don't complete one. */
    public void announcePurchase(String playerName, long credits, String packageName) {
        Bukkit.broadcast(Text.parse(
                "<gradient:#55FFFF:#5599FF><bold>✦ THANK YOU ✦</bold></gradient> <white><player></white> "
                        + "<gray>just purchased</gray> <white><package></white><gray>!</gray>",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("package", packageName == null || packageName.isBlank()
                        ? Formatting.format(credits) + " Credits"
                        : packageName)));
    }

    private void tick() {
        Progress current = progress.get();
        // Only the whole-dollar figure and the goal count are ever shown, so
        // rebuilding the title for every credit would be churn nobody sees.
        String key = (long) current.dollarsTowardGoal() + "/" + current.goalsCompleted();
        BossBar current_bar = bar;
        if (current_bar == null) {
            current_bar = BossBar.bossBar(titleFor(current), current.goalProgress(),
                    BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
            bar = current_bar;
            renderedTitleKey = key;
        } else if (!key.equals(renderedTitleKey)) {
            current_bar.name(titleFor(current));
            current_bar.progress(current.goalProgress());
            renderedTitleKey = key;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(current_bar);
        }
    }

    private Component titleFor(Progress current) {
        return Text.parse(
                "<gradient:#FFD700:#FF8C00><bold>★ DONATION GOAL</bold></gradient> <dark_gray>|</dark_gray> "
                        + "<white>$<raised></white><gray>/$100</gray> <dark_gray>|</dark_gray> "
                        + "<gold><bonus></gold>",
                Placeholder.unparsed("raised", String.format(Locale.ROOT, "%.0f", current.dollarsTowardGoal())),
                Placeholder.unparsed("bonus", current.goalsCompleted() == 0
                        ? "next goal = +0.1x coins forever"
                        : trim(current.permanentMultiplier()) + "x coins forever"));
    }

    /** Takes the bar off every screen on disable - the progress itself is persisted and comes straight back. */
    public void shutdown() {
        BossBar current = bar;
        if (current != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.hideBossBar(current);
            }
        }
        bar = null;
    }

    /** Admin-only correction, e.g. a refunded purchase - never announced, since it isn't a moment anyone should celebrate. */
    public void setProgress(long creditsTowardGoal, long goalsCompleted) {
        Progress before = progress.get();
        Progress after = new Progress(Math.max(0, creditsTowardGoal), Math.max(0, goalsCompleted), before.lifetimeCredits());
        progress.set(after);
        databaseManager.supplyAsync(() -> {
            store.save(after);
            return null;
        });
        tick();
    }

    /** "1.4" not "1.4000000000000001", and "2" not "2.0". */
    private static String trim(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }

    /** Grants the credits a purchase bought - kept here so the command has one call that does the whole job. */
    public static BigInteger creditsOf(long amount) {
        return BigInteger.valueOf(amount);
    }
}
