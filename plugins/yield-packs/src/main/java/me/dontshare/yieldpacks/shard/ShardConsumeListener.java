package me.dontshare.yieldpacks.shard;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Right-click a Shard in hand to consume it - see ShardService#consume for the actual permanent bonus. */
public final class ShardConsumeListener implements Listener {

    private final ShardItem shardItem;
    private final ShardService shardService;

    public ShardConsumeListener(ShardItem shardItem, ShardService shardService) {
        this.shardItem = shardItem;
        this.shardService = shardService;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        ShardType type = shardItem.typeOf(item);
        if (type == null) {
            return;
        }
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);

        boolean perfect = shardItem.isPerfect(item);
        double newMultiplier = shardService.consume(player, type, perfect);
        item.setAmount(item.getAmount() - 1);

        playConsumeSound(player, type, perfect);
        player.sendMessage(Text.parse("<green>Consumed - permanent <type> bonus is now <total>x.</green>",
                Placeholder.unparsed("type", type.name().toLowerCase(java.util.Locale.ROOT)),
                Placeholder.unparsed("total", Formatting.format(newMultiplier))));
    }

    /**
     * A power-absorption "poof" rather than the generic level-up jingle
     * used everywhere else - the puffer fish's own inflate sound reads
     * surprisingly well as "you just visibly gained power," and pitching
     * it a little differently per stat (each type gets its own fixed step
     * on the scale) means consuming a Damage shard doesn't sound
     * identical to a Gem one. Perfect shards go up a full octave and layer
     * a bright chime on top - the same "same base sound family, bigger and
     * higher for the rare version" trick the reveal reel's own tiers use.
     */
    private void playConsumeSound(Player player, ShardType type, boolean perfect) {
        float basePitch = switch (type) {
            case DAMAGE -> 0.8f;
            case COINS -> 1.0f;
            case GEMS -> 1.2f;
            case LUCK -> 1.4f;
            case ATTACK_SPEED -> 1.6f;
            case CRIT_CHANCE -> 1.8f;
        };
        float pitch = perfect ? basePitch * 1.5f : basePitch;
        player.playSound(player.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 0.7f, Math.min(2.0f, pitch));
        if (perfect) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        }
    }
}
