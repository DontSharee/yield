package me.dontshare.yieldanalytics;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** analytics.yml, read once at enable. */
public record AnalyticsConfig(
        boolean webEnabled, String bind, int port, List<String> whitelist, int viewerCodeMinutes,
        List<String> trustedProxies, List<String> hostnames, List<String> allowedOrigins,
        int retentionDays, int statsRefreshMinutes, int leftAfterDays, boolean countLoadTestBots,
        int summaryIntervalMinutes, double tpsBelow, double msptAbove, int sustainedSeconds, int alertCooldownMinutes,
        List<WebhookTarget> webhooks) {

    /** One place events are posted to. */
    public record WebhookTarget(String name, String url, Format format, Set<String> events) {

        public enum Format { DISCORD, JSON }

        public boolean wants(String event) {
            return events.contains(event);
        }
    }

    /** Replaces the whitelist in analytics.yml, keeping everything else (comments included) as it is. */
    public static void saveWhitelist(JavaPlugin plugin, List<String> entries) throws IOException {
        File file = new File(plugin.getDataFolder(), "analytics.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("web.whitelist", entries);
        yaml.save(file);
    }

    static AnalyticsConfig load(JavaPlugin plugin) {
        if (!new File(plugin.getDataFolder(), "analytics.yml").exists()) {
            plugin.saveResource("analytics.yml", false);
        }
        File file = new File(plugin.getDataFolder(), "analytics.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        List<WebhookTarget> targets = new ArrayList<>();
        for (Map<?, ?> raw : yaml.getMapList("webhooks.targets")) {
            if (Boolean.FALSE.equals(raw.get("enabled"))) {
                continue;
            }
            Object url = raw.get("url");
            if (!(url instanceof String urlText) || urlText.isBlank() || urlText.contains("/ID/TOKEN")) {
                continue;
            }
            Object formatRaw = raw.get("format");
            WebhookTarget.Format format = "json".equalsIgnoreCase(String.valueOf(formatRaw))
                    ? WebhookTarget.Format.JSON : WebhookTarget.Format.DISCORD;
            Set<String> events = new java.util.HashSet<>();
            if (raw.get("events") instanceof List<?> list) {
                for (Object event : list) {
                    events.add(String.valueOf(event).toLowerCase(Locale.ROOT));
                }
            }
            targets.add(new WebhookTarget(String.valueOf(raw.get("name")), urlText, format, events));
        }

        ConfigurationSection alerts = yaml.getConfigurationSection("webhooks.alerts");
        return new AnalyticsConfig(
                yaml.getBoolean("web.enabled", true),
                yaml.getString("web.bind", "0.0.0.0"),
                yaml.getInt("web.port", 8765),
                yaml.getStringList("web.whitelist"),
                Math.max(1, yaml.getInt("web.viewer-code-minutes", 10)),
                yaml.getStringList("web.trusted-proxies"),
                yaml.getStringList("web.hostnames"),
                yaml.getStringList("web.allowed-origins"),
                Math.max(1, yaml.getInt("retention-days", 90)),
                Math.max(1, yaml.getInt("stats-refresh-minutes", 5)),
                Math.max(1, yaml.getInt("left-after-days", 3)),
                yaml.getBoolean("count-load-test-bots", false),
                Math.max(5, yaml.getInt("webhooks.summary-interval-minutes", 60)),
                alerts != null ? alerts.getDouble("tps-below", 18.0) : 18.0,
                alerts != null ? alerts.getDouble("mspt-above", 45.0) : 45.0,
                alerts != null ? Math.max(10, alerts.getInt("sustained-seconds", 60)) : 60,
                alerts != null ? Math.max(1, alerts.getInt("cooldown-minutes", 15)) : 15,
                targets);
    }
}
