package me.dontshare.yieldevents.listener;

import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldevents.EventService;
import me.dontshare.yieldevents.data.SeasonalEvent;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Where an event's currency comes from: breaking cubes, the thing players
 * are doing anyway.
 * <p>
 * Deliberately a flat chance per cube rather than a share of the payout.
 * Coins scale by a factor of a million across the zone ladder, so anything
 * proportional would make a late-game player's Candy meaningless to an
 * early one and the event's egg either free or unreachable depending on
 * where you happened to be standing. A flat drop means an hour of play is
 * worth an hour of play, wherever it is spent - which is what a seasonal
 * currency has to be if the event is for everyone.
 */
public final class EventCurrencyListener implements Listener {

    private final EventService eventService;

    public EventCurrencyListener(EventService eventService) {
        this.eventService = eventService;
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        SeasonalEvent active = eventService.active();
        if (active == null || ThreadLocalRandom.current().nextDouble() >= active.dropChance()) {
            return;
        }
        int amount = active.dropMin() >= active.dropMax()
                ? active.dropMin()
                : ThreadLocalRandom.current().nextInt(active.dropMin(), active.dropMax() + 1);

        Player player = event.getPlayer();
        eventService.grant(player, active, amount);
        // The action bar belongs to the hatch reveal and the pity bar, and
        // chat would be a wall of it at several cubes a second, so the drop
        // announces itself the same way a cube's own payout does: a number
        // that appears and goes away.
        player.sendActionBar(Text.parse("<" + active.color() + ">+<amount> <currency></" + active.color() + ">",
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("currency", active.currencyName())));
    }
}
