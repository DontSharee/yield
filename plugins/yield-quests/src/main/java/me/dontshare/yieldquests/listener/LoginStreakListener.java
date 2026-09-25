package me.dontshare.yieldquests.listener;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldquests.LoginStreakService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

/** Announces the login streak the moment a player joins - see LoginStreakService for the actual day-boundary/reward logic. */
public final class LoginStreakListener implements Listener {

    private static final long DELAY_TICKS = 40L; // 2s - lets join messages/resource pack prompts settle first
    private static final Duration FADE_IN = Duration.ofMillis(300);
    private static final Duration STAY = Duration.ofSeconds(2, 500_000_000);
    private static final Duration FADE_OUT = Duration.ofMillis(500);

    private final JavaPlugin plugin;
    private final LoginStreakService service;
    private final me.dontshare.yieldquests.GiftDisplayService giftDisplay;

    public LoginStreakListener(JavaPlugin plugin, LoginStreakService service,
                               me.dontshare.yieldquests.GiftDisplayService giftDisplay) {
        this.plugin = plugin;
        this.service = service;
        this.giftDisplay = giftDisplay;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LoginStreakService.StreakResult result = service.recordLogin(player);
        if (result.alreadyCreditedToday()) {
            // A relog on the same real-world day - no new reward, no fanfare, nothing to announce twice.
            return;
        }
        // Queued straight away, not after the announcement delay: the coins
        // and diamonds live in this present now, and a player who leaves in
        // the next two seconds is paid them on the way out.
        giftDisplay.queueStreakGift(player, result.streak(), result.dayInCycle() == 7,
                result.coinsGranted(), result.diamondsGranted());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                announce(player, result);
            }
        }, DELAY_TICKS);
    }

    private void announce(Player player, LoginStreakService.StreakResult result) {
        Component main = Text.parse("<gradient:#FFD700:#FFAA00><bold>Day <streak> Streak!</bold></gradient>",
                Placeholder.unparsed("streak", String.valueOf(result.streak())));
        Component sub = buildRewardLine(result);
        player.showTitle(Title.title(main, sub, Title.Times.times(FADE_IN, STAY, FADE_OUT)));
        // A bright, gift-unwrapping kind of chime - distinct from every
        // other reward sound in this codebase, since "you just logged in"
        // should feel welcoming, not like a combat/crafting payoff.
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
        if (result.dayInCycle() == 7) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        player.sendMessage(Text.parse("<#FFD700>Welcome back! <sub></#FFD700>", Placeholder.component("sub", sub)));
        player.sendMessage(Text.parse("<gray>Your streak present is on its way - smack it open when it lands.</gray>"));
    }

    private Component buildRewardLine(LoginStreakService.StreakResult result) {
        StringBuilder line = new StringBuilder("<gray>+" + Formatting.format(result.coinsGranted()) + " coins");
        if (result.diamondsGranted() > 0) {
            line.append(", +").append(Formatting.format(result.diamondsGranted())).append(" diamonds");
        }
        if (result.creditsGranted() > 0) {
            line.append(", +").append(Formatting.format(result.creditsGranted())).append(" credits");
        }
        line.append("</gray>");
        return Text.parse(line.toString());
    }
}
