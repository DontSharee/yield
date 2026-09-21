package me.dontshare.yieldevents.listener;

import me.dontshare.yieldevents.EventService;
import me.dontshare.yieldevents.data.EventQuest;
import me.dontshare.yieldevents.data.SeasonalEvent;
import me.dontshare.yieldpacks.event.PackOpenedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** Counts event eggs hatched, for the quests that ask for them. */
public final class EventHatchListener implements Listener {

    private final EventService eventService;

    public EventHatchListener(EventService eventService) {
        this.eventService = eventService;
    }

    @EventHandler
    public void onHatch(PackOpenedEvent event) {
        SeasonalEvent active = eventService.active();
        if (active == null || !active.eggId().equals(event.getPackId())) {
            return;
        }
        eventService.addProgress(event.getPlayer(), active, EventQuest.Goal.EGGS_HATCHED, event.getRolls().size());
    }
}
