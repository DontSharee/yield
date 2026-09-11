package me.dontshare.yieldteams.listener;

import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldteams.TeamService;
import me.dontshare.yieldteams.TrophyItem;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Right-clicking a held Team Trophy deposits the WHOLE stack into your team's balance at once. */
public final class TrophyInteractListener implements Listener {

    private final TrophyItem trophyItem;
    private final TeamService teamService;

    public TrophyInteractListener(TrophyItem trophyItem, TeamService teamService) {
        this.trophyItem = trophyItem;
        this.teamService = teamService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (!trophyItem.isTrophy(item)) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        int amount = item.getAmount();
        if (teamService.depositTrophies(player, amount)) {
            item.setAmount(0);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
            player.sendMessage(Text.parse("<green>Deposited " + amount + " trophies into your team's balance!</green>"));
        } else {
            player.sendMessage(Text.parse("<red>You need to be in a team to deposit trophies.</red>"));
        }
    }
}
