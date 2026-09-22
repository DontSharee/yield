package me.dontshare.yieldevents.listener;

import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldevents.EventService;
import me.dontshare.yieldevents.data.EventQuest;
import me.dontshare.yieldevents.data.SeasonalEvent;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where an event's currency comes from: breaking cubes in the event's own
 * zone, which is what makes that zone somewhere to go.
 * <p>
 * Two deliberate choices about the rate:
 * <ul>
 *   <li><b>Flat, not a share of the payout.</b> Coins scale by a factor of
 *       a million across the zone ladder, so anything proportional would
 *       make an early player's balance worthless and a late player's free.
 *       A flat drop means an hour of play is worth an hour of play, which is
 *       what a seasonal currency has to be if the event is for everyone.</li>
 *   <li><b>Every cube in the zone pays, not a lucky few.</b> The event zone
 *       gives away almost no coins, so a player standing in it has already
 *       given up their normal income; being paid at a trickle on top of
 *       that would make the trip a punishment.</li>
 * </ul>
 * The event's own pets multiply it (see {@link SeasonalEvent#candyMultiplierFor}),
 * so the reward for playing the event is being better at the event.
 */
public final class EventCurrencyListener implements Listener {

    private final EventService eventService;
    private final YieldPacks packs;

    public EventCurrencyListener(EventService eventService, YieldPacks packs) {
        this.eventService = eventService;
        this.packs = packs;
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        SeasonalEvent active = eventService.active();
        if (active == null) {
            return;
        }
        Player player = event.getPlayer();
        boolean inZone = eventService.inEventZone(player, active);
        // Outside the event zone the old flat chance still applies, so the
        // event is visible wherever a player happens to be - just far
        // slower than going to where it is happening.
        if (!inZone && ThreadLocalRandom.current().nextDouble() >= active.dropChance()) {
            return;
        }

        int base = active.dropMin() >= active.dropMax()
                ? active.dropMin()
                : ThreadLocalRandom.current().nextInt(active.dropMin(), active.dropMax() + 1);
        double multiplier = active.candyMultiplierFor(equippedItemIds(player));
        long amount = Math.max(1, Math.round(base * multiplier));

        eventService.grant(player, active, amount);
        eventService.addProgress(player, active, EventQuest.Goal.CANDY_EARNED, amount);
        if (inZone) {
            eventService.addProgress(player, active, EventQuest.Goal.CUBES_BROKEN, 1);
        }
        // The action bar belongs to the hatch reveal and the pity bar, and
        // chat would be a wall of this at several cubes a second, so a drop
        // announces itself the way a cube's own payout does: a number that
        // appears and goes away.
        player.sendActionBar(Text.parse(
                "<" + active.color() + ">+<amount> <currency><bonus></" + active.color() + ">",
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.unparsed("currency", active.currencyName()),
                Placeholder.parsed("bonus", multiplier > 1.0
                        ? " <gray>(x" + String.format(java.util.Locale.ROOT, "%.2f", multiplier) + " from pets)</gray>" : "")));
    }

    /** What this player currently has equipped, by item id - the pets that might be the event's own. */
    private List<String> equippedItemIds(Player player) {
        PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
        if (profile == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (java.util.UUID petId : profile.getEquippedPetIds()) {
            profile.findPet(petId).ifPresent(pet -> ids.add(pet.getItemId()));
        }
        return ids;
    }
}
