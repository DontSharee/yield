package me.dontshare.yieldtutorial;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.packet.EntityClickRegistry;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldtutorial.command.TutorialCommand;
import me.dontshare.yieldtutorial.data.TutorialContentLoader.TutorialContent;
import me.dontshare.yieldtutorial.data.TutorialContentLoader;
import me.dontshare.yieldtutorial.listener.TutorialEventListener;
import me.dontshare.yieldtutorial.listener.TutorialJoinListener;
import me.dontshare.yieldtutorial.npc.TutorialNpcManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

public final class YieldTutorial extends JavaPlugin {

    private TutorialContentLoader contentLoader;
    private volatile TutorialContent content;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        contentLoader = new TutorialContentLoader(this, getLogger());
        content = contentLoader.load();

        World world = Bukkit.getWorld(content.npcWorld());
        if (world == null) {
            getLogger().warning("Tutorial NPC's configured world '" + content.npcWorld()
                    + "' isn't loaded - the guide will not be spawned. Fix tutorial.yml's npc.world and restart.");
            return;
        }
        Location npcLocation = new Location(world, content.npcX(), content.npcY(), content.npcZ(), content.npcYaw(), 0f);

        TutorialNpcManager npcManager = new TutorialNpcManager(this, content.npcName(), npcLocation);
        npcManager.start();

        TutorialService tutorialService = new TutorialService(() -> content, core.getPlayerProfileManager().getStore(), packs, npcManager);
        core.getScoreboardDisplay().setOverrideProvider(tutorialService::checklistFor);

        core.getListenerManager().register(new TutorialEventListener(tutorialService));
        core.getListenerManager().register(new TutorialJoinListener(tutorialService));
        EntityClickRegistry.registerInteract(npcManager.entityId(), tutorialService::onNpcInteract);

        CommandManager.register(this, TutorialCommand.build(tutorialService), "The new-player tutorial (/tutorial skip to opt out)");
    }

    /** Re-reads tutorial.yml - a player already mid-step just sees the new dialogue/reward the next time their current step is (re)shown; the NPC itself isn't respawned since its own location field is resolved once at startup. */
    public void reloadContent() {
        content = contentLoader.load();
    }
}
