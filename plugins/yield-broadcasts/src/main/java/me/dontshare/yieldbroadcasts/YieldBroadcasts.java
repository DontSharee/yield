package me.dontshare.yieldbroadcasts;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldbroadcasts.data.BroadcastsContentLoader;
import me.dontshare.yieldbroadcasts.data.BroadcastsContentLoader.BroadcastsContent;
import me.dontshare.yieldbroadcasts.listener.BroadcastEventListener;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import org.bukkit.plugin.java.JavaPlugin;

public final class YieldBroadcasts extends JavaPlugin {

    private BroadcastsContentLoader contentLoader;
    private volatile BroadcastsContent content;

    @Override
    public void onEnable() {
        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);

        contentLoader = new BroadcastsContentLoader(this);
        content = contentLoader.load();

        core.getListenerManager().register(new BroadcastEventListener(() -> content, packs));

        core.getAdminCommandRegistry().register(buildAdminCommand());
    }

    public void reloadContent() {
        content = contentLoader.load();
    }

    private LiteralCommandNode<CommandSourceStack> buildAdminCommand() {
        return Commands.literal("broadcasts")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-broadcasts content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
