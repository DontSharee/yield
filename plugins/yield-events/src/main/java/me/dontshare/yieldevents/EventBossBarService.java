package me.dontshare.yieldevents;

import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldevents.data.SeasonalEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;

/**
 * The always-on reminder that an event is running, and that it will not be
 * for long.
 * <p>
 * One shared bar rather than one per player: every viewer sees the same
 * event with the same days left, so there is nothing per-player to say. It
 * shows the player's own balance in the title, which is the one thing that
 * does differ - so the bar is rebuilt per viewer only when that number
 * changes, not every tick.
 * <p>
 * The bar disappears entirely when no event is running. An empty progress
 * bar advertising nothing is worse than no bar at all.
 */
public final class EventBossBarService {

    private static final long TICK_INTERVAL = 20L;

    private final JavaPlugin plugin;
    private final EventService eventService;
    private BossBar bar;
    private String lastRendered;

    public EventBossBarService(JavaPlugin plugin, EventService eventService) {
        this.plugin = plugin;
        this.eventService = eventService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    private void tick() {
        SeasonalEvent event = eventService.active();
        if (event == null) {
            hide();
            return;
        }
        LocalDate today = LocalDate.now();
        long daysLeft = event.daysRemaining(today);
        long totalDays = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(event.start(), event.end()) + 1L);
        float progress = Math.max(0f, Math.min(1f, (float) daysLeft / totalDays));

        String state = event.id() + "|" + daysLeft;
        if (bar == null) {
            bar = BossBar.bossBar(title(event, daysLeft), progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        } else if (!state.equals(lastRendered)) {
            bar.name(title(event, daysLeft));
            bar.progress(progress);
        }
        lastRendered = state;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(bar);
        }
    }

    private net.kyori.adventure.text.Component title(SeasonalEvent event, long daysLeft) {
        return Text.parse("<" + event.color() + "><bold><name></bold></" + event.color() + ">"
                        + " <gray>-</gray> <white><days></white> <gray>day<plural> left</gray>",
                Placeholder.unparsed("name", event.displayName()),
                Placeholder.unparsed("days", String.valueOf(daysLeft)),
                Placeholder.unparsed("plural", daysLeft == 1 ? "" : "s"));
    }

    private void hide() {
        if (bar == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
        bar = null;
        lastRendered = null;
    }
}
