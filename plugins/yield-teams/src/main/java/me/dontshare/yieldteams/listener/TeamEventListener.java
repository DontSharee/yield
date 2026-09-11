package me.dontshare.yieldteams.listener;

import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldteams.TrophyItem;
import me.dontshare.yieldteams.data.TeamsContentLoader.TeamsContent;
import me.dontshare.yieldzones.event.OreCubeKilledEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Rolls a trophy drop on every ore cube kill - a rare, luck-modified chance, same formula candy/gem drops already use. Reacts to the existing event rather than yield-zones needing any awareness that teams exist. */
public final class TeamEventListener implements Listener {

    private final Supplier<TeamsContent> content;
    private final TrophyItem trophyItem;
    private final YieldPacks packs;

    public TeamEventListener(Supplier<TeamsContent> content, TrophyItem trophyItem, YieldPacks packs) {
        this.content = content;
        this.trophyItem = trophyItem;
        this.packs = packs;
    }

    @EventHandler
    public void onCubeKilled(OreCubeKilledEvent event) {
        Player player = event.getPlayer();
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
        double luck = packs.getLuckService().totalLuckMultiplier(profile);
        double chance = content.get().trophy().dropChance() * luck;
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }
        ItemStack trophy = trophyItem.create(content.get().trophy());
        player.getInventory().addItem(trophy).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.sendMessage(Text.parse("<#FFD700>You found a Team Trophy!</#FFD700>"));
    }
}
