package me.dontshare.yieldtutorial.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Loads tutorial.yml into an in-memory registry - mirrors yield-quests' QuestContentLoader's style. */
public final class TutorialContentLoader {

    public record TutorialContent(String npcName, String npcWorld, double npcX, double npcY, double npcZ,
                                   float npcYaw, List<TutorialStep> steps) {
    }

    private final JavaPlugin plugin;
    private final Logger logger;

    public TutorialContentLoader(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    public TutorialContent load() {
        plugin.saveResource("tutorial.yml", false);
        File file = new File(plugin.getDataFolder(), "tutorial.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection npc = config.getConfigurationSection("npc");
        String npcName = npc != null ? npc.getString("name", "Guide") : "Guide";
        String npcWorld = npc != null ? npc.getString("world", "world") : "world";
        double npcX = npc != null ? npc.getDouble("x", 0) : 0;
        double npcY = npc != null ? npc.getDouble("y", 64) : 64;
        double npcZ = npc != null ? npc.getDouble("z", 0) : 0;
        float npcYaw = npc != null ? (float) npc.getDouble("yaw", 0) : 0f;

        return new TutorialContent(npcName, npcWorld, npcX, npcY, npcZ, npcYaw, loadSteps(config));
    }

    private List<TutorialStep> loadSteps(YamlConfiguration config) {
        List<TutorialStep> steps = new ArrayList<>();
        for (Map<?, ?> raw : config.getMapList("steps")) {
            Object idValue = raw.get("id");
            if (idValue == null) {
                logger.warning("Tutorial step is missing an 'id' - skipping.");
                continue;
            }
            String id = String.valueOf(idValue);

            List<String> dialogue = new ArrayList<>();
            if (raw.get("dialogue") instanceof List<?> lines) {
                for (Object line : lines) {
                    dialogue.add(String.valueOf(line));
                }
            }

            Object triggerValue = raw.get("completes-on");
            CompletionTrigger trigger;
            try {
                trigger = CompletionTrigger.valueOf(String.valueOf(triggerValue));
            } catch (IllegalArgumentException e) {
                logger.warning("Tutorial step '" + id + "' has an invalid completes-on '" + triggerValue + "' - skipping.");
                continue;
            }

            String label = raw.get("label") != null ? String.valueOf(raw.get("label")) : id;
            int goal = raw.get("goal") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            long rewardCoins = raw.get("reward-coins") instanceof Number n ? n.longValue() : 0;
            int rewardGems = raw.get("reward-gems") instanceof Number n ? n.intValue() : 0;
            steps.add(new TutorialStep(id, label, dialogue, trigger, goal, Math.max(0, rewardCoins), Math.max(0, rewardGems)));
        }
        return steps;
    }
}
