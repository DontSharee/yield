package me.dontshare.yieldanalytics.collect;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The tutorial's step names, read from yield-tutorial's own tutorial.yml -
 * the funnel is labelled with them. Re-read on each stats run, so an edited
 * tutorial shows up without a restart. Empty if the tutorial isn't installed.
 */
public final class TutorialSteps {

    private volatile List<String> labels = List.of();

    public List<String> labels() {
        return labels;
    }

    public int count() {
        return labels.size();
    }

    public void reload() {
        Plugin tutorial = Bukkit.getPluginManager().getPlugin("yield-tutorial");
        if (tutorial == null) {
            labels = List.of();
            return;
        }
        File file = new File(tutorial.getDataFolder(), "tutorial.yml");
        if (!file.exists()) {
            return;
        }
        List<String> fresh = new ArrayList<>();
        for (Map<?, ?> step : YamlConfiguration.loadConfiguration(file).getMapList("steps")) {
            Object label = step.get("label");
            fresh.add(label != null ? String.valueOf(label) : String.valueOf(step.get("id")));
        }
        labels = List.copyOf(fresh);
    }
}
