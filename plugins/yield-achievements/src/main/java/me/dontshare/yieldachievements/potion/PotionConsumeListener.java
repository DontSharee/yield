package me.dontshare.yieldachievements.potion;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Sound;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/** Right-click a potion item in hand to drink it - see PotionItem/PotionService. */
public final class PotionConsumeListener implements Listener {

    private final PotionItem potionItem;
    private final PotionService potionService;

    public PotionConsumeListener(PotionItem potionItem, PotionService potionService) {
        this.potionItem = potionItem;
        this.potionService = potionService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        String potionId = potionItem.potionIdOf(item);
        if (potionId == null) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        PotionDefinition def = potionService.consume(player, potionId);
        if (def == null) {
            return;
        }
        item.setAmount(item.getAmount() - 1);
        player.sendMessage(Text.parse(
                "<#4BD9FF><bold>Potion!</bold></#4BD9FF> <gray>+</gray><white>x<mult> <stat></white> <gray>for</gray> <white><seconds>s</white>",
                Placeholder.unparsed("mult", def.multiplier() == Math.rint(def.multiplier()) ? String.valueOf((long) def.multiplier()) : String.valueOf(def.multiplier())),
                Placeholder.unparsed("stat", def.stat().name().replace('_', ' ')),
                Placeholder.unparsed("seconds", Formatting.format((double) def.durationSeconds()))));
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1.2f);
    }
}
