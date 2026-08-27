package me.dontshare.yield.listener;

import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Central place other Yield plugins register their listeners through,
 * rather than each plugin registering directly against its own instance.
 */
public final class ListenerManager {

    private final JavaPlugin plugin;

    public ListenerManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }
}
