package me.dontshare.yieldskilltree.listener;

import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldskilltree.event.PrestigeEvent;
import me.dontshare.yieldskilltree.event.SkillNodeBoughtEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.meta.FireworkMeta;

import java.time.Duration;

/**
 * Both node purchases and Prestige used to be plain chat text with zero
 * sound/particle at all - the quietest systems in the whole game despite
 * skill trees being something players interact with constantly. Two very
 * different scales of feedback here on purpose: a node buy is light and
 * quick (it can fire many times in one click via auto-buy, so nothing
 * title-sized), Prestige is the actual "reset everything for a permanent
 * bonus" moment and gets the same weight Rebirth's own fanfare does.
 */
public final class SkillFanfareListener implements Listener {

    private static final Duration FADE_IN = Duration.ofMillis(300);
    private static final Duration STAY = Duration.ofSeconds(2, 500_000_000);
    private static final Duration FADE_OUT = Duration.ofMillis(500);

    @EventHandler
    public void onNodeBought(SkillNodeBoughtEvent event) {
        Player player = event.getPlayer();
        // A mechanical toggle-switch click - fits "activating a node" on a
        // tree literally laid out as buttons, and short enough that
        // several in a row (auto-buy) reads as a satisfying "cha-ching
        // cha-ching" rather than overlapping into noise.
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.5f, 1.3f);
        player.spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.5);
    }

    @EventHandler
    public void onPrestige(PrestigeEvent event) {
        Player player = event.getPlayer();
        Component main = Text.parse("<gradient:#FFD700:#B15CFF><bold>PRESTIGE!</bold></gradient>");
        Component sub = Text.parse("<gray>Prestige #<total></gray>", Placeholder.unparsed("total", String.valueOf(event.getTotalPrestiges())));
        player.showTitle(Title.title(main, sub, Title.Times.times(FADE_IN, STAY, FADE_OUT)));
        // A triumphant "something permanent just unlocked" chime, distinct
        // from Rebirth's own anchor-charge sound despite both being
        // full-reset prestige loops.
        player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1f, 1f);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 0.7f);
        launchFirework(player);
    }

    private void launchFirework(Player player) {
        Firework firework = player.getWorld().spawn(player.getLocation(), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BURST)
                .withColor(Color.fromRGB(0xFFD700), Color.fromRGB(0xB15CFF))
                .withFade(Color.WHITE)
                .withTrail()
                .build());
        meta.setPower(1);
        firework.setFireworkMeta(meta);
        firework.detonate();
    }
}
