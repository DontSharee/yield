package me.dontshare.yieldlootboxes;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldlootboxes.data.LootboxDefinition;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.function.Supplier;

/** Right-click a lootbox item in hand to open it - see LootboxItem/LootboxService. Mirrors yield-achievements' PotionConsumeListener exactly. */
public final class LootboxConsumeListener implements Listener {

    private final LootboxItem lootboxItem;
    private final LootboxService service;
    private final Supplier<Map<String, LootboxDefinition>> content;

    public LootboxConsumeListener(LootboxItem lootboxItem, LootboxService service, Supplier<Map<String, LootboxDefinition>> content) {
        this.lootboxItem = lootboxItem;
        this.service = service;
        this.content = content;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        String boxId = lootboxItem.boxIdOf(item);
        if (boxId == null) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        LootboxDefinition box = content.get().get(boxId);
        if (box == null) {
            return;
        }
        // A box can hold a pet, so a full bag keeps the box unopened.
        var storage = me.dontshare.yieldpacks.YieldPacks.getPlugin(me.dontshare.yieldpacks.YieldPacks.class).getBagStorageService();
        var profile = me.dontshare.yieldpacks.YieldPacks.getPlugin(me.dontshare.yieldpacks.YieldPacks.class).getPlayerStore().getOrCreate(player.getUniqueId());
        if (!storage.hasRoom(player, profile, 1)) {
            player.sendMessage(Text.parse("<red><msg></red>", Placeholder.unparsed("msg", storage.fullMessage(player, profile))));
            return;
        }
        item.setAmount(item.getAmount() - 1);
        service.open(player, box);
        player.sendMessage(Text.parse("<#4BD9FF><bold>Opened!</bold></#4BD9FF> <gray><name></gray>",
                Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(box.displayName()))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
    }
}
