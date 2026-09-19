package me.dontshare.yieldbroadcasts.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Loads broadcasts.yml - one config block per "big moment" trigger, each independently enable-able with its own message template. */
public final class BroadcastsContentLoader {

    public record PackOpenConfig(boolean enabled, Set<String> rarityIds, String messageTemplate) {
    }

    public record FusionConfig(boolean enabled, Set<String> tierNames, String messageTemplate) {
    }

    public record RebirthConfig(boolean enabled, int every, String messageTemplate) {
    }

    /** Fires on any pull whose "1 in N" clears {@code minOneIn} - the one broadcast that scales with how lucky a pull actually was rather than with which tier it belonged to. */
    public record LuckyPullConfig(boolean enabled, long minOneIn, String messageTemplate) {
    }

    public record SimpleConfig(boolean enabled, String messageTemplate) {
    }

    public record BroadcastsContent(PackOpenConfig packOpen, SimpleConfig huge, LuckyPullConfig luckyPull,
                                     FusionConfig fusion, RebirthConfig rebirth,
                                     SimpleConfig prestige, SimpleConfig zoneUnlock, SimpleConfig teamCreate,
                                     SimpleConfig shardFind, SimpleConfig worldBoss) {
    }

    private final JavaPlugin plugin;

    public BroadcastsContentLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public BroadcastsContent load() {
        plugin.saveResource("broadcasts.yml", false);
        File file = new File(plugin.getDataFolder(), "broadcasts.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        return new BroadcastsContent(
                loadPackOpen(config.getConfigurationSection("pack-open")),
                loadSimple(config.getConfigurationSection("huge"), defaultMessage("huge")),
                loadLuckyPull(config.getConfigurationSection("lucky-pull")),
                loadFusion(config.getConfigurationSection("fusion")),
                loadRebirth(config.getConfigurationSection("rebirth")),
                loadSimple(config.getConfigurationSection("prestige"), defaultMessage("prestige")),
                loadSimple(config.getConfigurationSection("zone-unlock"), defaultMessage("zone-unlock")),
                loadSimple(config.getConfigurationSection("team-create"), defaultMessage("team-create")),
                loadSimple(config.getConfigurationSection("shard-find"), defaultMessage("shard-find")),
                loadSimple(config.getConfigurationSection("world-boss"), defaultMessage("world-boss"))
        );
    }

    private PackOpenConfig loadPackOpen(ConfigurationSection section) {
        if (section == null) {
            return new PackOpenConfig(false, Set.of(), "");
        }
        Set<String> rarities = new HashSet<>(section.getStringList("rarities"));
        return new PackOpenConfig(section.getBoolean("enabled", true), rarities,
                section.getString("message", defaultMessage("pack-open")));
    }

    private LuckyPullConfig loadLuckyPull(ConfigurationSection section) {
        if (section == null) {
            return new LuckyPullConfig(false, Long.MAX_VALUE, "");
        }
        return new LuckyPullConfig(section.getBoolean("enabled", true),
                Math.max(1L, section.getLong("min-one-in", 100_000L)),
                section.getString("message", defaultMessage("lucky-pull")));
    }

    private FusionConfig loadFusion(ConfigurationSection section) {
        if (section == null) {
            return new FusionConfig(false, Set.of(), "");
        }
        Set<String> tiers = new HashSet<>(section.getStringList("tiers"));
        return new FusionConfig(section.getBoolean("enabled", true), tiers,
                section.getString("message", defaultMessage("fusion")));
    }

    private RebirthConfig loadRebirth(ConfigurationSection section) {
        if (section == null) {
            return new RebirthConfig(false, 10, "");
        }
        return new RebirthConfig(section.getBoolean("enabled", true), Math.max(1, section.getInt("every", 10)),
                section.getString("message", defaultMessage("rebirth")));
    }

    private SimpleConfig loadSimple(ConfigurationSection section, String fallback) {
        if (section == null) {
            return new SimpleConfig(false, "");
        }
        return new SimpleConfig(section.getBoolean("enabled", true), section.getString("message", fallback));
    }

    private String defaultMessage(String key) {
        return "<gray>[" + key + "]</gray>";
    }
}
