package me.dontshare.yieldachievements.listener;

import me.dontshare.yieldachievements.AchievementService;
import me.dontshare.yieldachievements.MilestoneService;
import me.dontshare.yieldachievements.data.GameAction;
import me.dontshare.yieldpacks.event.PackOpenedEvent;
import me.dontshare.yieldpacks.event.PetCandyFedEvent;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldpacks.event.PetFusedEvent;
import me.dontshare.yieldpacks.event.PetLeveledUpEvent;
import me.dontshare.yieldpacks.event.PetUnequippedEvent;
import me.dontshare.yieldrebirth.event.RebirthEvent;
import me.dontshare.yieldskilltree.event.PrestigeEvent;
import me.dontshare.yieldskilltree.event.SkillNodeBoughtEvent;
import me.dontshare.yieldteams.event.TeamCreatedEvent;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import me.dontshare.yieldzones.event.WorldBossKilledEvent;
import me.dontshare.yieldzones.event.ZoneEnteredEvent;
import me.dontshare.yieldzones.event.ZoneUnlockedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;

/**
 * Bridges every cross-plugin event this server fires into BOTH {@link
 * AchievementService} and {@link MilestoneService} at once - same event set
 * yield-quests' own QuestEventListener already wires up (see that class),
 * plus {@link WorldBossKilledEvent}, which has no single "player" (a boss
 * kill is shared) so every contributor gets credited individually.
 */
public final class ProgressEventListener implements Listener {

    private final AchievementService achievementService;
    private final MilestoneService milestoneService;

    public ProgressEventListener(AchievementService achievementService, MilestoneService milestoneService) {
        this.achievementService = achievementService;
        this.milestoneService = milestoneService;
    }

    private void trigger(Player player, GameAction action, long amount) {
        achievementService.incrementProgress(player, action, amount);
        milestoneService.incrementProgress(player, action, amount);
    }

    @EventHandler
    public void onPackOpened(PackOpenedEvent event) {
        // A multi-open (see yield-packs' PackOpenService#tryOpenMany) fires
        // ONE event carrying every roll from the batch, not one event per
        // pack - OPEN_PACK must scale by rolls().size(), not a flat 1, or a
        // 24x multi-open would only ever count as 1 pack toward any
        // achievement/milestone.
        int count = event.getRolls().size();
        trigger(event.getPlayer(), GameAction.OPEN_PACK, count);
        trigger(event.getPlayer(), GameAction.OBTAIN_PET, count);
    }

    @EventHandler
    public void onPetEquipped(PetEquippedEvent event) {
        trigger(event.getPlayer(), GameAction.EQUIP_PET, 1);
    }

    @EventHandler
    public void onPetUnequipped(PetUnequippedEvent event) {
        trigger(event.getPlayer(), GameAction.UNEQUIP_PET, 1);
    }

    @EventHandler
    public void onPetFused(PetFusedEvent event) {
        trigger(event.getPlayer(), GameAction.FUSE_PET, 1);
    }

    @EventHandler
    public void onCandyFed(PetCandyFedEvent event) {
        trigger(event.getPlayer(), GameAction.FEED_CANDY, 1);
    }

    @EventHandler
    public void onPetLeveledUp(PetLeveledUpEvent event) {
        trigger(event.getPlayer(), GameAction.LEVEL_UP_PET, 1);
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        trigger(event.getPlayer(), GameAction.KILL_CUBE, 1);
        trigger(event.getPlayer(), GameAction.EARN_COINS, event.getCoinsEarned());
    }

    @EventHandler
    public void onZoneEntered(ZoneEnteredEvent event) {
        trigger(event.getPlayer(), GameAction.ENTER_ZONE, 1);
    }

    @EventHandler
    public void onZoneUnlocked(ZoneUnlockedEvent event) {
        trigger(event.getPlayer(), GameAction.UNLOCK_ZONE, 1);
    }

    @EventHandler
    public void onRebirth(RebirthEvent event) {
        trigger(event.getPlayer(), GameAction.REBIRTH, event.getRebirthsGained());
    }

    @EventHandler
    public void onPrestige(PrestigeEvent event) {
        trigger(event.getPlayer(), GameAction.PRESTIGE, 1);
    }

    @EventHandler
    public void onSkillNodeBought(SkillNodeBoughtEvent event) {
        trigger(event.getPlayer(), GameAction.BUY_SKILL_NODE, 1);
    }

    @EventHandler
    public void onTeamCreated(TeamCreatedEvent event) {
        trigger(event.getPlayer(), GameAction.CREATE_TEAM, 1);
    }

    @EventHandler
    public void onWorldBossKilled(WorldBossKilledEvent event) {
        for (Map.Entry<UUID, Long> entry : event.getDamageByPlayer().entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            trigger(player, GameAction.KILL_WORLD_BOSS, 1);
            trigger(player, GameAction.DEAL_BOSS_DAMAGE, entry.getValue());
        }
    }
}
