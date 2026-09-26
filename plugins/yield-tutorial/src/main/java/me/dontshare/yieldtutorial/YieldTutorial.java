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
        core.getListenerManager().register(new TutorialJoinListener(tutorialService, npcManager));
        EntityClickRegistry.registerInteract(npcManager.entityId(), tutorialService::onNpcInteract);

        CommandManager.register(this, TutorialCommand.build(tutorialService), "The new-player tutorial (/tutorial skip to opt out)");
        registerEdits(core, tutorialService);
    }

    @Override
    public void onDisable() {
        me.dontshare.yieldcore.admin.PlayerEdits.unregisterAll(this);
    }

    /** "step:N" (1-based), "done" or "skipped" - what the analytics site's editor sets. */
    private void registerEdits(YieldCore core, TutorialService tutorialService) {
        me.dontshare.yieldcore.admin.PlayerEdits.register(new me.dontshare.yieldcore.admin.PlayerEdits.Stat<>(
                "tutorial", "Tutorial", "Progress", me.dontshare.yieldcore.admin.PlayerEdits.Kind.CHOICE, 0, 0,
                "Moving them back to a step shows it to them again; done or skipped hides the guide.",
                core.getPlayerProfileManager().getStore(),
                profile -> profile.isTutorialSkipped() ? "skipped"
                        : profile.getTutorialStep() >= content.steps().size() ? "done"
                        : "step:" + (profile.getTutorialStep() + 1),
                (profile, value) -> {
                    String before = profile.isTutorialSkipped() ? "skipped"
                            : profile.getTutorialStep() >= content.steps().size() ? "done"
                            : "step:" + (profile.getTutorialStep() + 1);
                    int count = content.steps().size();
                    if (value.equals("skipped")) {
                        profile.setTutorialSkipped(true);
                    } else if (value.equals("done")) {
                        profile.setTutorialSkipped(false);
                        profile.setTutorialStep(count);
                    } else if (value.startsWith("step:")) {
                        int step = (int) me.dontshare.yieldcore.admin.PlayerEdits.number(value.substring(5), 1, Math.max(1, count));
                        profile.setTutorialSkipped(false);
                        profile.setTutorialStep(step - 1);
                    } else {
                        throw new IllegalArgumentException("Expected step:N, done or skipped.");
                    }
                    profile.setTutorialStepProgress(0);
                    return me.dontshare.yieldcore.admin.PlayerEdits.restore("tutorial", before);
                },
                () -> {
                    java.util.List<me.dontshare.yieldcore.admin.PlayerEdits.Option> options = new java.util.ArrayList<>();
                    java.util.List<me.dontshare.yieldtutorial.data.TutorialStep> steps = content.steps();
                    for (int i = 0; i < steps.size(); i++) {
                        options.add(new me.dontshare.yieldcore.admin.PlayerEdits.Option("step:" + (i + 1),
                                "Step " + (i + 1) + ": " + steps.get(i).label(), null));
                    }
                    options.add(new me.dontshare.yieldcore.admin.PlayerEdits.Option("done", "Completed", null));
                    options.add(new me.dontshare.yieldcore.admin.PlayerEdits.Option("skipped", "Skipped", null));
                    return options;
                },
                tutorialService::refresh, this));
    }

    /** Re-reads tutorial.yml - a player already mid-step just sees the new dialogue/reward the next time their current step is (re)shown; the NPC itself isn't respawned since its own location field is resolved once at startup. */
    public void reloadContent() {
        content = contentLoader.load();
    }
}
