package me.dontshare.yieldquests.listener;

import me.dontshare.yieldpacks.event.PackOpenedEvent;
import me.dontshare.yieldpacks.event.PetCandyFedEvent;
import me.dontshare.yieldpacks.event.PetEquippedEvent;
import me.dontshare.yieldpacks.event.PetFusedEvent;
import me.dontshare.yieldpacks.event.PetLeveledUpEvent;
import me.dontshare.yieldpacks.event.PetUnequippedEvent;
import me.dontshare.yieldquests.QuestService;
import me.dontshare.yieldquests.RankQuestService;
import me.dontshare.yieldquests.data.GameAction;
import me.dontshare.yieldrebirth.event.RebirthEvent;
import me.dontshare.yieldskilltree.event.PrestigeEvent;
import me.dontshare.yieldskilltree.event.SkillNodeBoughtEvent;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import me.dontshare.yieldzones.event.ZoneEnteredEvent;
import me.dontshare.yieldzones.event.ZoneUnlockedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Bridges every cross-plugin event this server fires into both
 * {@link QuestService#incrementProgress} (today's daily quests) and
 * {@link RankQuestService#incrementProgress} (the always-3-active Rank
 * Quest board) - see {@link GameAction} for the full trigger list this maps
 * onto. One listener, two destinations, so nothing double-registers for the
 * same real event.
 */
public final class QuestEventListener implements Listener {

    private final QuestService questService;
    private final RankQuestService rankQuestService;

    public QuestEventListener(QuestService questService, RankQuestService rankQuestService) {
        this.questService = questService;
        this.rankQuestService = rankQuestService;
    }

    @EventHandler
    public void onPackOpened(PackOpenedEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.OPEN_PACK, 1);
        questService.incrementProgress(event.getPlayer(), GameAction.OBTAIN_PET, event.getRolls().size());
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.OPEN_PACK, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.OBTAIN_PET, event.getRolls().size());
    }

    @EventHandler
    public void onPetEquipped(PetEquippedEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.EQUIP_PET, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.EQUIP_PET, 1);
    }

    @EventHandler
    public void onPetUnequipped(PetUnequippedEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.UNEQUIP_PET, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.UNEQUIP_PET, 1);
    }

    @EventHandler
    public void onPetFused(PetFusedEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.FUSE_PET, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.FUSE_PET, 1);
    }

    @EventHandler
    public void onCandyFed(PetCandyFedEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.FEED_CANDY, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.FEED_CANDY, 1);
    }

    @EventHandler
    public void onPetLeveledUp(PetLeveledUpEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.LEVEL_UP_PET, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.LEVEL_UP_PET, 1);
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.KILL_CUBE, 1);
        questService.incrementProgress(event.getPlayer(), GameAction.EARN_COINS, (int) Math.min(Integer.MAX_VALUE, event.getCoinsEarned()));
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.KILL_CUBE, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.EARN_COINS, (int) Math.min(Integer.MAX_VALUE, event.getCoinsEarned()));
    }

    @EventHandler
    public void onZoneEntered(ZoneEnteredEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.ENTER_ZONE, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.ENTER_ZONE, 1);
    }

    @EventHandler
    public void onZoneUnlocked(ZoneUnlockedEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.UNLOCK_ZONE, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.UNLOCK_ZONE, 1);
    }

    @EventHandler
    public void onRebirth(RebirthEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.REBIRTH, event.getRebirthsGained());
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.REBIRTH, event.getRebirthsGained());
    }

    @EventHandler
    public void onPrestige(PrestigeEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.PRESTIGE, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.PRESTIGE, 1);
    }

    @EventHandler
    public void onSkillNodeBought(SkillNodeBoughtEvent event) {
        questService.incrementProgress(event.getPlayer(), GameAction.BUY_SKILL_NODE, 1);
        rankQuestService.incrementProgress(event.getPlayer(), GameAction.BUY_SKILL_NODE, 1);
    }
}
