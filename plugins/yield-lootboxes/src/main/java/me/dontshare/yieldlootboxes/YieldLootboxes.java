package me.dontshare.yieldlootboxes;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldlootboxes.command.LootboxAdminCommand;
import me.dontshare.yieldlootboxes.data.LootboxContentLoader;
import me.dontshare.yieldlootboxes.data.LootboxDefinition;
import me.dontshare.yieldpacks.YieldPacks;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class YieldLootboxes extends JavaPlugin {

    private LootboxContentLoader contentLoader;
    private volatile Map<String, LootboxDefinition> content;
    private LootboxService lootboxService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        contentLoader = new LootboxContentLoader(this, getLogger());
        content = contentLoader.load();

        LootboxItem lootboxItem = new LootboxItem(this, packs::getItemRegistry);
        lootboxService = new LootboxService(() -> content, packs, lootboxItem);

        Bukkit.getPluginManager().registerEvents(new LootboxConsumeListener(lootboxItem, lootboxService, () -> content), this);
        core.getAdminCommandRegistry().register(LootboxAdminCommand.build(this, lootboxService));
    }

    /** Re-reads lootboxes.yml - existing LootboxService instance keeps working against the same, now-updated content supplier. */
    public void reloadContent() {
        content = contentLoader.load();
    }

    public LootboxService getLootboxService() {
        return lootboxService;
    }
}
