package me.dontshare.yieldquests;

import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.database.PlayerStores;
import me.dontshare.yieldquests.data.QuestProfile;
import me.dontshare.yieldquests.data.RankQuestDefinition;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldquests.command.DailyCommand;
import me.dontshare.yieldquests.command.QuestCommand;
import me.dontshare.yieldquests.command.QuestsAdminCommand;
import me.dontshare.yieldquests.data.PresentsContentLoader.PresentsContent;
import me.dontshare.yieldquests.data.PresentsContentLoader;
import me.dontshare.yieldquests.data.QuestContentLoader.QuestContent;
import me.dontshare.yieldquests.data.QuestContentLoader;
import me.dontshare.yieldquests.data.RankQuestContentLoader;
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
    private RankQuestContentLoader rankQuestContentLoader;
    private volatile QuestContent questContent;
    private volatile PresentsContent presentsContent;
    private volatile List<RankQuestDefinition> rankQuestPool;
    private QuestService questService;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        questContentLoader = new QuestContentLoader(this, getLogger());
        questContent = questContentLoader.load();
        presentsContentLoader = new PresentsContentLoader(this, getLogger());
        presentsContent = presentsContentLoader.load();
        rankQuestContentLoader = new RankQuestContentLoader(this, getLogger());
        rankQuestPool = rankQuestContentLoader.load();

        PlayerDataStore<QuestProfile> questStore = PlayerStores.register(
                this, core.getListenerManager(), core.getDatabaseManager(),
                "quests", QuestProfile.class, QuestProfile::new, "quest data");
        questService = new QuestService(() -> questContent, packs.getPlayerStore(), questStore, packs);
        RankQuestService rankQuestService = new RankQuestService(() -> rankQuestPool, packs.getPlayerStore(), questStore, packs);
        PresentsService presentsService = new PresentsService(() -> presentsContent, packs);
        LoginStreakService loginStreakService = new LoginStreakService(packs, questStore);

        core.getListenerManager().register(new QuestEventListener(questService, rankQuestService));
        core.getListenerManager().register(new PresentsSessionListener(presentsService));
        GiftDisplayService giftDisplay = new GiftDisplayService(this, presentsService, packs,
                JavaPlugin.getPlugin(me.dontshare.yieldzones.YieldZones.class));
        giftDisplay.start();
        core.getListenerManager().register(new LoginStreakListener(this, loginStreakService, giftDisplay));

        // No separate Rank Quest GUI/command - the board renders inline in
        // yield-packs' own RankupGui (row 0), reachable via /rankup,
        // /ranks, or /rankquests, all the same screen now.
        packs.getRankupGui().setQuestSource(rankQuestService::viewsFor);

        QuestGui questGui = new QuestGui(packs.getPlayerStore(), () -> questContent, questService, core.getGuiManager(),
                packs::getItemRegistry);
        PresentsGui presentsGui = new PresentsGui(presentsService, giftDisplay, core.getGuiManager());

        CommandManager.register(this, QuestCommand.build(questGui), "View and claim today's daily quests", List.of());
        CommandManager.register(this, DailyCommand.build(presentsGui), "Claim your session's daily presents", List.of());
        core.getAdminCommandRegistry().register(QuestsAdminCommand.build(this, questService));
    }

    /** Re-reads quests.yml, daily.yml and rank-quests.yml. */
    public void reloadContent() {
        questContent = questContentLoader.load();
        presentsContent = presentsContentLoader.load();
        rankQuestPool = rankQuestContentLoader.load();
    }
}
