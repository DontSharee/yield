package me.dontshare.yieldevents.data;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                    Math.max(1, s.getLong("egg-price", 100)),
                    s.getString("zone"),
                    loadPetBonuses(s.getConfigurationSection("pet-candy-bonus")),
                    loadQuests(id, s),
                    loadShop(id, s)));
        }
        return events;
    }

    private Map<String, Double> loadPetBonuses(ConfigurationSection section) {
        Map<String, Double> bonuses = new LinkedHashMap<>();
        if (section == null) {
            return bonuses;
        }
        for (String petId : section.getKeys(false)) {
            bonuses.put(petId, section.getDouble(petId));
        }
        return bonuses;
    }

    private List<EventQuest> loadQuests(String eventId, ConfigurationSection event) {
        List<EventQuest> quests = new ArrayList<>();
        ConfigurationSection section = event.getConfigurationSection("quests");
        if (section == null) {
            return quests;
        }
        for (String questId : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(questId);
            if (s == null) {
                continue;
            }
            EventQuest.Goal goal;
            try {
                goal = EventQuest.Goal.valueOf(s.getString("goal", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                logger.warning("Event '" + eventId + "' quest '" + questId + "' has an unknown goal '"
                        + s.getString("goal") + "' - skipping. Valid: CANDY_EARNED, CUBES_BROKEN, EGGS_HATCHED.");
                continue;
            }
            quests.add(new EventQuest(
                    questId,
                    s.getString("display-name", questId),
                    s.getStringList("description"),
                    goal,
                    Math.max(1, s.getLong("target", 1)),
                    Math.max(0, s.getLong("reward-candy", 0)),
                    Math.max(0, s.getLong("reward-coins", 0)),
                    Math.max(0, s.getLong("reward-diamonds", 0)),
                    List.copyOf(s.getStringList("reward-commands"))));
        }
        return quests;
    }

    /**
     * The shop shelf. An entry whose material is unreadable is skipped
     * rather than silently rendered as a stone block - a shelf item nobody
     * recognises is one nobody buys, and a typo should say so in the log.
     */
    private List<EventShopEntry> loadShop(String eventId, ConfigurationSection event) {
        List<EventShopEntry> entries = new ArrayList<>();
        ConfigurationSection section = event.getConfigurationSection("shop");
        if (section == null) {
            return entries;
        }
        for (String entryId : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(entryId);
            if (s == null) {
                continue;
            }
            String rawMaterial = s.getString("material", "CHEST");
            Material material = Material.matchMaterial(rawMaterial.toUpperCase(Locale.ROOT));
            if (material == null) {
                logger.warning("Event '" + eventId + "' shop entry '" + entryId + "' has an unknown material '"
                        + rawMaterial + "' - skipping.");
                continue;
            }
            List<String> commands = List.copyOf(s.getStringList("commands"));
            if (commands.isEmpty()) {
                logger.warning("Event '" + eventId + "' shop entry '" + entryId
                        + "' has no commands, so buying it would hand over nothing - skipping.");
                continue;
            }
            entries.add(new EventShopEntry(
                    entryId,
                    s.getString("display-name", entryId),
                    material,
                    Math.max(1, s.getLong("price", 1)),
                    Math.max(0, s.getInt("limit", 0)),
                    commands));
        }
        return entries;
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
