package me.dontshare.yieldpacks.economy;

import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;

/**
 * One value per player, recomputed at most once a server tick.
 * <p>
 * The global multipliers (damage, attack speed, coins, luck...) are each a
 * walk over every plugin's registered provider - skill tree nodes, claimed
 * blocktree tiers, enchant slots, upgrades, potions - and combat asks for
 * them on every single pet attack, several pets a second per player. They
 * can't change within a tick in any way combat would notice, so the hot
 * paths read through this; menus keep calling the live methods, so a value
 * shown right after a purchase is never a tick stale.
 */
public final class TickMemo {

    private record Entry(int tick, double value) {
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final ToDoubleFunction<PackPlayerProfile> compute;

    public TickMemo(ToDoubleFunction<PackPlayerProfile> compute) {
        this.compute = compute;
    }

    public double get(PackPlayerProfile profile) {
        int now = Bukkit.getCurrentTick();
        UUID id = profile.getPlayerId();
        Entry entry = entries.get(id);
        if (entry != null && entry.tick() == now) {
            return entry.value();
        }
        double value = compute.applyAsDouble(profile);
        entries.put(id, new Entry(now, value));
        return value;
    }

    public void forget(UUID playerId) {
        entries.remove(playerId);
    }
}
