package me.dontshare.yieldtrade.currency;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Shift + right-click a held note to redeem it - mirrors yield-packs' own PetRedeemListener gesture. */
public final class CurrencyNoteRedeemListener implements Listener {

    private final CurrencyNoteService noteService;

    public CurrencyNoteRedeemListener(CurrencyNoteService noteService) {
        this.noteService = noteService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!noteService.getNoteItem().isNote(held)) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        noteService.redeem(player, held);
    }
}
