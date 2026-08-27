package me.dontshare.yield.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.dontshare.yield.Yield;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Thin PlaceholderAPI adapter over {@link PlaceholderRegistry} - exposes
 * every registered key under the "yield" identifier (e.g.
 * %yield_username%) so other plugins (holograms, chat, etc.) can read
 * Yield data without depending on us directly.
 */
public final class YieldExpansion extends PlaceholderExpansion {

    private final Yield plugin;
    private final PlaceholderRegistry registry;

    public YieldExpansion(Yield plugin, PlaceholderRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "yield";
    }

    @Override
    public @NotNull String getAuthor() {
        return "YieldMC";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        return registry.resolve(params, player);
    }
}
