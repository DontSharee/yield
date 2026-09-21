package me.dontshare.yieldevents.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** Loads events.yml - same warn-and-skip (never crash) handling of a bad entry every other content loader here uses. */
public final class EventContentLoader {

    private final JavaPlugin plugin;
    private final Logger logger;

    public EventContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public List<SeasonalEvent> load() {
        plugin.saveResource("events.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "events.yml"));
        ConfigurationSection section = config.getConfigurationSection("events");
        List<SeasonalEvent> events = new ArrayList<>();
        if (section == null) {
            return events;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            LocalDate start = parseDate(id, s.getString("start"));
            LocalDate end = parseDate(id, s.getString("end"));
            String eggId = s.getString("egg");
            if (start == null || end == null || eggId == null) {
                logger.warning("Event '" + id + "' is missing start/end/egg - skipping.");
                continue;
            }
            if (end.isBefore(start)) {
                logger.warning("Event '" + id + "' ends before it starts - skipping.");
                continue;
            }
            events.add(new SeasonalEvent(
                    id,
                    s.getString("display-name", id),
                    s.getString("color", "#FFFFFF"),
                    start,
                    end,
                    s.getString("currency.name", "Tokens"),
                    s.getDouble("currency.drop-chance", 0.05),
                    Math.max(1, s.getInt("currency.drop-min", 1)),
                    Math.max(1, s.getInt("currency.drop-max", 1)),
                    eggId,
                    Math.max(1, s.getLong("egg-price", 100))));
        }
        return events;
    }

    private LocalDate parseDate(String eventId, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            logger.warning("Event '" + eventId + "' has an unreadable date '" + raw + "' - expected YYYY-MM-DD.");
            return null;
        }
    }
}
