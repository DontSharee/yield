package me.dontshare.yieldmining.enchant;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/** Right-click any pickaxe (in either hand, air or a block) to open the pickaxe enchant menu - matches the reference's own "on right click with any pickaxe". */
public final class PickaxeMenuListener implements Listener {

    private static final Set<Material> PICKAXES = EnumSet.of(
            Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE,
            Material.GOLDEN_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE
    );

    private final PickaxeUpgradeGui upgradeGui;

    public PickaxeMenuListener(PickaxeUpgradeGui upgradeGui) {
        this.upgradeGui = upgradeGui;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !PICKAXES.contains(item.getType())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        upgradeGui.open(player);
    }
}
