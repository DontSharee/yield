package me.dontshare.yieldevents;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldevents.data.SeasonalEvent;
import me.dontshare.yieldpackstations.PackStationService;
import org.bukkit.entity.Player;

/**
 * Pays for an event egg in that event's own currency.
 * <p>
 * yield-packstations knows how to take coins, diamonds and credits; Candy
 * is this plugin's business, so this is the adapter it registers rather
 * than a fourth currency bolted onto an egg definition. The station asks
 * what it costs, whether the player has it, and then to take it - and hands
 * it back if the hatch is refused after payment.
 */
public final class EventStationCharge implements PackStationService.AlternateCharge {

    private final EventService eventService;

    public EventStationCharge(EventService eventService) {
        this.eventService = eventService;
    }

    @Override
    public int affordable(Player player, int cap) {
        SeasonalEvent event = eventService.active();
        if (event == null) {
            return 0;
        }
        long held = eventService.balance(player, event);
        return (int) Math.max(0, Math.min(cap, held / event.eggPrice()));
    }

    @Override
    public boolean charge(Player player, int count) {
        SeasonalEvent event = eventService.active();
        return event != null && eventService.take(player, event, event.eggPrice() * count);
    }

    @Override
    public void refund(Player player, int count) {
        SeasonalEvent event = eventService.active();
        if (event != null) {
            eventService.refund(player, event, event.eggPrice() * count);
        }
    }

    @Override
    public String priceLabel() {
        SeasonalEvent event = eventService.active();
        if (event == null) {
            return "&7-";
        }
        return "&e" + Formatting.format((double) event.eggPrice()) + " " + event.currencyName();
    }
}
