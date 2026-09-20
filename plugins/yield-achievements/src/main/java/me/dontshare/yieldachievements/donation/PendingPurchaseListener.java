package me.dontshare.yieldachievements.donation;

import me.dontshare.yieldcore.database.DatabaseManager;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.List;

/** Hands over anything bought while this player was offline, the moment they join. */
public final class PendingPurchaseListener implements Listener {

    private final JavaPlugin plugin;
    private final DatabaseManager databaseManager;
    private final PendingPurchaseStore store;
    private final PlayerDataStore<PackPlayerProfile> packStore;

    public PendingPurchaseListener(JavaPlugin plugin, DatabaseManager databaseManager, PendingPurchaseStore store,
                                    PlayerDataStore<PackPlayerProfile> packStore) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.store = store;
        this.packStore = packStore;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        databaseManager.supplyAsync(() -> store.claimAll(name)).thenAccept(pending -> {
            if (pending.isEmpty()) {
                return;
            }
            // Back to the main thread before touching the profile - the store
            // is only safe to mutate there.
            Bukkit.getScheduler().runTask(plugin, () -> deliver(player, pending));
        });
    }

    private void deliver(Player player, List<PendingPurchaseStore.Pending> pending) {
        if (!player.isOnline()) {
            return;
        }
        long total = 0;
        for (PendingPurchaseStore.Pending row : pending) {
            total += row.credits();
        }
        PackPlayerProfile profile = packStore.getOrCreate(player.getUniqueId());
        profile.setCredits(profile.getCredits().add(BigInteger.valueOf(total)));
        packStore.save(player.getUniqueId());

        player.sendMessage(Text.parse(
                "<green>Thank you for your purchase! <white><credits></white> Credits have been added to your account.</green>",
                Placeholder.unparsed("credits", Formatting.format(total))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }
}
