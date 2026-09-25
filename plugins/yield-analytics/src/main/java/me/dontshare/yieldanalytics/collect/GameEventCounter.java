package me.dontshare.yieldanalytics.collect;

import me.dontshare.yieldanalytics.data.ActivityStore;
import me.dontshare.yieldmining.event.OreMinedEvent;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.event.PackOpenedEvent;
import me.dontshare.yieldpacks.event.PetCandyFedEvent;
import me.dontshare.yieldpacks.event.PetFusedEvent;
import me.dontshare.yieldpacks.event.PetLeveledUpEvent;
import me.dontshare.yieldpacks.event.ShardFoundEvent;
import me.dontshare.yieldpacks.roll.PackRollService;
import me.dontshare.yieldrebirth.event.RebirthEvent;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import me.dontshare.yieldzones.event.WorldBossKilledEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.UUID;

/**
 * Turns the game's own events into hourly counters - what players are
 * actually doing, how much of it, and what it pays. Bots from the load test
 * are left out, so a test never reads as a busy hour.
 */
public final class GameEventCounter implements Listener {

    private final ActivityStore activity;
    private final YieldPacks packs;

    public GameEventCounter(ActivityStore activity, YieldPacks packs) {
        this.activity = activity;
        this.packs = packs;
    }

    private static boolean ignored(Player player) {
        return player == null || Exclusions.excluded(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCubeKilled(OreCubeKilledEvent event) {
        if (ignored(event.getPlayer())) {
            return;
        }
        activity.count("cubeKills");
        activity.count("kills_" + event.getTier().material().name().toLowerCase(Locale.ROOT));
        activity.count("coinsFromCubes", event.getCoinsEarned());
        activity.count("diamondsFromCubes", event.getDiamondsEarned());
        if (event.getBonusMultiplier() > 1.0) {
            activity.count("bonusCubes");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBossKilled(WorldBossKilledEvent event) {
        activity.count("bossKills");
        activity.count("boss_" + event.getDefinition().id());
        int fighters = 0;
        for (UUID id : event.getDamageByPlayer().keySet()) {
            if (!Exclusions.excluded(id)) {
                fighters++;
            }
        }
        activity.count("bossFighters", fighters);
        activity.count("coinsFromBosses", event.getRewardCoins());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEggsOpened(PackOpenedEvent event) {
        if (ignored(event.getPlayer())) {
            return;
        }
        activity.count("hatches");
        activity.count("eggsHatched", event.getRolls().size());
        activity.count("egg_" + event.getPackId(), event.getRolls().size());
        for (PackRollService.RollResult roll : event.getRolls()) {
            activity.count("hatch_" + roll.item().rarityId());
            if (roll.huge()) {
                activity.count("hugeHatched");
            }
            if (roll.pet() != null && roll.pet().isShiny()) {
                activity.count("shinyHatched");
            }
            if (roll.firstTimeCollected()) {
                activity.count("newCollectionEntries");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRebirth(RebirthEvent event) {
        if (ignored(event.getPlayer())) {
            return;
        }
        activity.count("rebirthEvents");
        activity.count("rebirths", event.getRebirthsGained());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOreMined(OreMinedEvent event) {
        if (ignored(event.getPlayer())) {
            return;
        }
        activity.count("oresMined");
        activity.count("coinsFromMining", event.getCoinsEarned());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFused(PetFusedEvent event) {
        if (!ignored(event.getPlayer())) {
            activity.count("petsFused");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelUp(PetLeveledUpEvent event) {
        if (!ignored(event.getPlayer())) {
            activity.count("petLevelUps");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShard(ShardFoundEvent event) {
        if (ignored(event.getPlayer())) {
            return;
        }
        activity.count("shardsFound");
        if (event.isPerfect()) {
            activity.count("perfectShards");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCandy(PetCandyFedEvent event) {
        if (!ignored(event.getPlayer())) {
            activity.count("candiesFed");
        }
    }

    public YieldPacks packs() {
        return packs;
    }
}
