package me.dontshare.yieldquests;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldquests.command.DailyCommand;
import me.dontshare.yieldquests.command.QuestCommand;
import me.dontshare.yieldquests.command.QuestsAdminCommand;
import me.dontshare.yieldquests.data.PresentsContentLoader.PresentsContent;
import me.dontshare.yieldquests.data.PresentsContentLoader;
import me.dontshare.yieldquests.data.QuestContentLoader.QuestContent;
import me.dontshare.yieldquests.data.QuestContentLoader;
import me.dontshare.yieldquests.gui.PresentsGui;
import me.dontshare.yieldquests.gui.QuestGui;
import me.dontshare.yieldquests.listener.LoginStreakListener;
import me.dontshare.yieldquests.listener.PresentsSessionListener;
import me.dontshare.yieldquests.listener.QuestEventListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class YieldQuests extends JavaPlugin {

    private QuestContentLoader questContentLoader;
    private PresentsContentLoader presentsContentLoader;
    private volatile QuestContent questContent;
    private volatile PresentsContent presentsContent;
    private QuestService questService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        questContentLoader = new QuestContentLoader(this, getLogger());
        questContent = questContentLoader.load();
        presentsContentLoader = new PresentsContentLoader(this, getLogger());
        presentsContent = presentsContentLoader.load();

        questService = new QuestService(() -> questContent, packs.getPlayerStore(), packs);
        PresentsService presentsService = new PresentsService(() -> presentsContent, packs);
        LoginStreakService loginStreakService = new LoginStreakService(packs);

        core.getListenerManager().register(new QuestEventListener(questService));
        core.getListenerManager().register(new PresentsSessionListener(presentsService));
        core.getListenerManager().register(new LoginStreakListener(this, loginStreakService));

        QuestGui questGui = new QuestGui(packs.getPlayerStore(), () -> questContent, questService, core.getGuiManager(),
                packs::getItemRegistry);
        PresentsGui presentsGui = new PresentsGui(presentsService, core.getGuiManager());

        CommandManager.register(this, QuestCommand.build(questGui), "View and claim today's daily quests", List.of());
        CommandManager.register(this, DailyCommand.build(presentsGui), "Claim your session's daily presents", List.of());
        core.getAdminCommandRegistry().register(QuestsAdminCommand.build(this, questService));
    }

    /** Re-reads quests.yml and daily.yml. */
    public void reloadContent() {
        questContent = questContentLoader.load();
        presentsContent = presentsContentLoader.load();
    }
}
