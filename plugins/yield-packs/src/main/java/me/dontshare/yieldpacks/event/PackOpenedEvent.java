package me.dontshare.yieldpacks.event;

import me.dontshare.yieldpacks.roll.PackRollService;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.List;

/**
 * Fired from {@link me.dontshare.yieldpacks.roll.PackOpenService#tryOpen} right
 * after a pack is successfully opened - covers both a manual "Open 1" click and
 * each auto-open tick, since both funnel through that one method. Lets other
 * plugins (e.g. yield-quests) react to a pack open without yield-packs needing
 * any awareness of them.
 */
public final class PackOpenedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String packId;
    private final List<PackRollService.RollResult> rolls;

    public PackOpenedEvent(Player player, String packId, List<PackRollService.RollResult> rolls) {
        this.player = player;
        this.packId = packId;
        this.rolls = rolls;
    }

    public Player getPlayer() {
        return player;
    }

    public String getPackId() {
        return packId;
    }

    public List<PackRollService.RollResult> getRolls() {
        return rolls;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
