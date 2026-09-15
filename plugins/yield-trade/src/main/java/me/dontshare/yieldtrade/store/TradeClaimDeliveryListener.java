package me.dontshare.yieldtrade.store;

import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/** Hands back anything a player was owed from a trade that ended while they were away. */
public final class TradeClaimDeliveryListener implements Listener {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final TradeClaimStore claimStore;

    public TradeClaimDeliveryListener(JavaPlugin plugin, DatabaseManager databaseManager, TradeClaimStore claimStore) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.claimStore = claimStore;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        databaseManager.supplyAsync(() -> claimStore.findIdsFor(playerId))
                .thenAccept(ids -> {
                    if (ids.isEmpty()) {
                        return;
                    }
                    deliverEach(playerId, ids);
                });
    }

    /**
     * Each row is taken with its own atomic delete and handed over
     * immediately, so a disconnect partway through leaves the rest still
     * owed rather than consumed.
     */
    private void deliverEach(UUID playerId, List<UUID> ids) {
        for (UUID claimId : ids) {
            databaseManager.supplyAsync(() -> claimStore.claimOne(claimId, playerId))
                    .thenAccept(item -> {
                        if (item == null) {
                            return;
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Player player = Bukkit.getPlayer(playerId);
                            if (player == null) {
                                // Gone again before this landed - put it back rather than lose it.
                                databaseManager.supplyAsync(() -> {
                                    claimStore.insert(playerId, List.of(item), "redelivery");
                                    return true;
                                });
                                return;
                            }
                            player.getInventory().addItem(item).values().forEach(leftover ->
                                    player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                            player.sendMessage(Text.parse(
                                    "<green>Returned <name> from an interrupted trade.</green>",
                                    Placeholder.component("name", itemName(item))));
                        });
                    });
        }
    }

    private net.kyori.adventure.text.Component itemName(ItemStack item) {
        return item.getItemMeta() != null && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().displayName()
                : net.kyori.adventure.text.Component.text(item.getType().name().toLowerCase().replace('_', ' '));
    }
}
