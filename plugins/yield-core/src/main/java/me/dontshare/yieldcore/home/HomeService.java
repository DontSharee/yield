package me.dontshare.yieldcore.home;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.player.PlayerProfile;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

/** Named home locations, capped per player - see {@code home.yml}'s "max-homes". */
public final class HomeService {

    public enum SetResult { SUCCESS, LIMIT_REACHED }

    private final PlayerDataStore<PlayerProfile> store;
    private volatile int maxHomes;

    public HomeService(PlayerDataStore<PlayerProfile> store, FileConfiguration config) {
        this.store = store;
        reload(config);
    }

    public void reload(FileConfiguration config) {
        this.maxHomes = Math.max(1, config.getInt("max-homes", 5));
    }

    public int getMaxHomes() {
        return maxHomes;
    }

    public Map<String, HomePoint> homesOf(Player player) {
        return store.getOrCreate(player.getUniqueId()).getHomes();
    }

    public SetResult setHome(Player player, String name) {
        PlayerProfile profile = store.getOrCreate(player.getUniqueId());
        String key = name.toLowerCase(Locale.ROOT);
        if (!profile.getHomes().containsKey(key) && profile.getHomes().size() >= maxHomes) {
            return SetResult.LIMIT_REACHED;
        }
        profile.getHomes().put(key, new HomePoint(player.getLocation()));
        store.save(player.getUniqueId());
        return SetResult.SUCCESS;
    }

    /** Null if no home by that name exists, or its world isn't currently loaded. */
    public Location getHome(Player player, String name) {
        HomePoint point = store.getOrCreate(player.getUniqueId()).getHomes().get(name.toLowerCase(Locale.ROOT));
        return point == null ? null : point.toLocation();
    }

    public boolean deleteHome(Player player, String name) {
        PlayerProfile profile = store.getOrCreate(player.getUniqueId());
        boolean removed = profile.getHomes().remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            store.save(player.getUniqueId());
        }
        return removed;
    }
}
