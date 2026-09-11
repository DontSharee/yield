package me.dontshare.yieldrebirth.listener;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldrebirth.event.RebirthEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.meta.FireworkMeta;

import java.time.Duration;

/**
 * Real fanfare for the game's own core prestige loop - previously a rebirth
 * (any real number of them, not just a milestone) got NOTHING beyond a
 * single chat line, despite being the biggest single decision a player
 * makes. Fires from {@link RebirthEvent} itself rather than being baked
 * into any one trigger, so it's identical whether a rebirth came from
 * {@code /rebirth}, the confirm dialog, or a physical Rebirth Machine (see
 * yield-zonemachines) - one fanfare, every path.
 */
public final class RebirthFanfareListener implements Listener {

    private static final Duration FADE_IN = Duration.ofMillis(300);
    private static final Duration STAY = Duration.ofSeconds(2, 500_000_000);
    private static final Duration FADE_OUT = Duration.ofMillis(500);

    @EventHandler
    public void onRebirth(RebirthEvent event) {
        Player player = event.getPlayer();
        Component main = Text.parse("<gradient:#55FF7F:#4BD9FF><bold>REBIRTH!</bold></gradient>");
        Component sub = Text.parse("<gray>Rebirth <total> - &7<gray>+</gray><white><gained></white> <gray>this time</gray>",
                Placeholder.unparsed("total", Formatting.format((double) event.getTotalRebirths())),
                Placeholder.unparsed("gained", String.valueOf(event.getRebirthsGained())));
        player.showTitle(Title.title(main, sub, Title.Times.times(FADE_IN, STAY, FADE_OUT)));
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.6f);
        // A literal "charging up for rebirth" sound rather than a generic
        // toast chime - couldn't be a more on-the-nose fit for this exact
        // moment.
        player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 1f);
        launchFirework(player.getLocation());
    }

    /** A real vanilla firework, launched at the player's own feet and detonating almost instantly - visible to everyone nearby, not just the rebirthing player, same "other players see it happen" social payoff the Perfect Shard broadcast already has. */
    private void launchFirework(Location at) {
        Firework firework = at.getWorld().spawn(at, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BURST)
                .withColor(Color.fromRGB(0x55FF7F), Color.fromRGB(0x4BD9FF))
                .withFade(Color.WHITE)
                .withTrail()
                .withFlicker()
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
        firework.detonate();
    }
}
