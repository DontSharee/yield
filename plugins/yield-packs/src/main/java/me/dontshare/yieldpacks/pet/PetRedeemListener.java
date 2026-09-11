package me.dontshare.yieldpacks.pet;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Shift+right-click a withdrawn-pet item in hand to redeem it back into your Bag - see PetWithdrawItem. */
public final class PetRedeemListener implements Listener {

    private final PetWithdrawItem withdrawItem;
    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final PetDisplayService petDisplayService;

    public PetRedeemListener(PetWithdrawItem withdrawItem, PlayerDataStore<PackPlayerProfile> playerStore, PetDisplayService petDisplayService) {
        this.withdrawItem = withdrawItem;
        this.playerStore = playerStore;
        this.petDisplayService = petDisplayService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick() || !event.getPlayer().isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        PetInstance pet = withdrawItem.read(item);
        if (pet == null) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);

        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        profile.getPets().add(pet);
        item.setAmount(item.getAmount() - 1);
        playerStore.save(player.getUniqueId());
        petDisplayService.refresh(player);
        player.sendMessage(Text.parse("<green>Redeemed - check your Bag.</green>"));
    }
}
