package me.dontshare.yieldpacks.player;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A one-time refund for eggs bought under the old model and never opened.
 * <p>
 * Packs used to be bought into storage and opened later; eggs are paid for
 * at the moment they hatch. Anyone who was holding a stockpile when that
 * changed would otherwise just lose it, silently, which is the one outcome
 * a currency change must never have - so every stored egg is paid back at
 * the price it was bought for, once, and the player is told.
 * <p>
 * Self-disarming rather than version-flagged: nothing writes to
 * {@code storedPacks} any more, so an empty map means this has already run
 * (or there was never anything to refund) and the listener costs a map
 * lookup per join forever after. The field itself stays on
 * {@link PackPlayerProfile} for exactly as long as it takes every player to
 * log in once.
 */
public final class StoredEggRefundListener implements Listener {

    private static final long DELAY_TICKS = 40L;

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<PackContentLoader.ContentSnapshot> content;

    public StoredEggRefundListener(JavaPlugin plugin, PlayerDataStore<PackPlayerProfile> store,
                                    Supplier<PackContentLoader.ContentSnapshot> content) {
        this.plugin = plugin;
        this.store = store;
        this.content = content;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Late enough that the player is loaded in and will actually see
        // the message, same reasoning as StarterPetJoinListener's delay.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                refund(player);
            }
        }, DELAY_TICKS);
    }

    private void refund(Player player) {
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        Map<String, Integer> stored = profile.getStoredPacks();
        if (stored.isEmpty()) {
            return;
        }

        BigInteger coins = BigInteger.ZERO;
        BigInteger diamonds = BigInteger.ZERO;
        int eggs = 0;
        for (Map.Entry<String, Integer> entry : stored.entrySet()) {
            PackDefinition pack = content.get().packs().find(entry.getKey()).orElse(null);
            int count = Math.max(0, entry.getValue());
            if (pack == null || count == 0) {
                continue;
            }
            eggs += count;
            coins = coins.add(BigInteger.valueOf(pack.coinCost()).multiply(BigInteger.valueOf(count)));
            diamonds = diamonds.add(BigInteger.valueOf(pack.diamondCost()).multiply(BigInteger.valueOf(count)));
        }
        // Cleared whether or not anything resolved - an entry naming a pack
        // that no longer exists cannot be priced, and leaving it behind
        // would re-run this every single join forever.
        stored.clear();
        if (eggs == 0) {
            store.save(player.getUniqueId());
            return;
        }

        profile.setCoins(profile.getCoins().add(coins));
        profile.setDiamonds(profile.getDiamonds().add(diamonds));
        store.save(player.getUniqueId());

        player.sendMessage(Text.parse(
                "<#4BD9FF><bold>Eggs</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Eggs are hatched at their station now,"
                        + " so your <white><eggs></white> unopened ones were refunded:</gray> <green>$<coins></green><extra>",
                Placeholder.unparsed("eggs", String.valueOf(eggs)),
                Placeholder.unparsed("coins", Formatting.format(coins)),
                Placeholder.parsed("extra", diamonds.signum() > 0
                        ? " <gray>and</gray> <aqua>" + Formatting.format(diamonds) + " diamonds</aqua>" : "")));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
    }
}
