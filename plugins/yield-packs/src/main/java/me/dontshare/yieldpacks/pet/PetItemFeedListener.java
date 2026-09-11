package me.dontshare.yieldpacks.pet;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.ItemRegistry;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import me.dontshare.yieldpacks.economy.EquipmentService;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.function.Supplier;

/**
 * Clicking a withdrawn pet item (see {@link PetWithdrawItem}) while holding
 * a recognized "apply to pet" item (candy, a forged held item, etc. - see
 * {@link PetItemHandler}) applies it directly to that withdrawn pet, same
 * as clicking its icon in the Bag would for an equipped/bagged one -
 * without needing the pet to actually be re-deposited first. Scoped to
 * real {@link PlayerInventory} slots only (never a custom Gui's own top
 * inventory - a Gui never holds a real withdrawn-pet item in the first
 * place, so this never fights that system's own click handling).
 */
public final class PetItemFeedListener implements Listener {

    private final PetWithdrawItem withdrawItem;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<ItemRegistry> itemRegistry;
    private final Supplier<RarityRegistry> rarityRegistry;
    private final EquipmentService equipmentService;
    private final PetItemHandler handlers;

    public PetItemFeedListener(PetWithdrawItem withdrawItem, PlayerDataStore<PackPlayerProfile> store,
                                Supplier<ItemRegistry> itemRegistry, Supplier<RarityRegistry> rarityRegistry,
                                EquipmentService equipmentService, PetItemHandler handlers) {
        this.withdrawItem = withdrawItem;
        this.store = store;
        this.itemRegistry = itemRegistry;
        this.rarityRegistry = rarityRegistry;
        this.equipmentService = equipmentService;
        this.handlers = handlers;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getClickedInventory() instanceof PlayerInventory) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        PetInstance pet = withdrawItem.read(current);
        if (pet == null || cursor == null || cursor.getType().isAir()) {
            return;
        }
        if (!handlers.apply(player, pet, cursor)) {
            return;
        }
        event.setCancelled(true);

        ItemDefinition item = itemRegistry.get().find(pet.getItemId()).orElse(null);
        if (item == null) {
            // The pet's own item type vanished from config - can't safely rebuild the physical item's lore/icon, so bail out without consuming anything.
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        double effectiveDamage = equipmentService.effectiveDamage(profile, pet);

        event.setCurrentItem(withdrawItem.create(item, pet, rarity, effectiveDamage));
        event.setCursor(cursor.getAmount() <= 0 ? null : cursor);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        player.sendMessage(Text.parse("<green>Applied to your withdrawn pet.</green>"));
    }
}
