package me.dontshare.yieldcore.restrictions;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Yield is a pack/pet economy game, not a survival-building one - none of
 * hunger, taking damage, tool durability, or editing the world are meant to
 * be part of it anywhere on the server, not just inside zones. Every event
 * here is silently cancelled (no message) rather than explained, per intent -
 * this is a baseline server rule, not something a player did wrong. Creative
 * mode is still exempt from the world-editing restrictions (block break/
 * place, bucket use) so building/decorating zones stays possible; it's not
 * exempted from the others since vanilla Creative already ignores hunger,
 * damage, and tool durability on its own.
 * <p>
 * Also blanket-blocks manually wearing anything as a helmet (see the
 * helmet-slot methods below) - several pets/withdrawn-pet items render as
 * PLAYER_HEAD (vanilla lets you right-click ANY head-shaped item to wear it),
 * and this server has no reason for a player to ever have something sitting
 * in that slot at all.
 */
public final class GameplayRestrictionsListener implements Listener {

    /** The raw PlayerInventory slot index for the helmet - the standard Bukkit convention (36-39 = boots/legs/chest/helmet). */
    private static final int HELMET_SLOT = 39;

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }

    /**
     * Vanilla's own "right-click to wear" behavior (works for real armor AND
     * for any head-shaped item - player/mob heads, carved pumpkins) - blocked
     * for every plain right-click. Sneak right-click is deliberately exempt:
     * that gesture is reserved for a tagged item's own custom interaction
     * (e.g. yield-packs' pet-redeem listener), which already denies the use-
     * item action itself for the one item it actually recognizes - nothing
     * else has any legitimate sneak-right-click behavior to preserve, and
     * exempting sneaking here entirely (rather than trying to guess which
     * listener runs first) means the two features can never fight over the
     * same click.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getPlayer().isSneaking() || !event.getAction().isRightClick()) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && item.getType().getEquipmentSlot() == EquipmentSlot.HEAD) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    /** Manual GUI-based equipping - dragging or (shift-)clicking anything into the helmet slot of a player's own inventory screen. Custom chest GUIs (this plugin's own {@code Gui}) never expose an armor slot at all, so the only real target is the vanilla inventory screen. */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        boolean directlyIntoHelmetSlot = event.getInventory().getType() == InventoryType.PLAYER
                && event.getRawSlot() == HELMET_SLOT;
        boolean shiftClickedFromMainInventory = event.getClick().isShiftClick()
                && event.getClickedInventory() instanceof PlayerInventory
                && event.getCurrentItem() != null
                && event.getCurrentItem().getType().getEquipmentSlot() == EquipmentSlot.HEAD;
        if (directlyIntoHelmetSlot || shiftClickedFromMainInventory) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getType() == InventoryType.PLAYER && event.getRawSlots().contains(HELMET_SLOT)) {
            event.setCancelled(true);
        }
    }

    /** Belt-and-suspenders backstop, matching this codebase's own established self-heal philosophy (see e.g. OreCubeService#reconcileCubeBlocks) - catches anything the click/drag/interact guards above didn't (a plugin, a command, a dispenser) rather than trusting those to be exhaustive. Drops the item at their feet instead of deleting it. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        clearHelmetIfPresent(event.getPlayer());
    }

    public static void startHelmetReconcileTask(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                clearHelmetIfPresent(player);
            }
        }, 100L, 100L);
    }

    private static void clearHelmetIfPresent(Player player) {
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet != null && helmet.getType() != org.bukkit.Material.AIR) {
            player.getInventory().setHelmet(null);
            player.getWorld().dropItemNaturally(player.getLocation(), helmet);
        }
    }
}
