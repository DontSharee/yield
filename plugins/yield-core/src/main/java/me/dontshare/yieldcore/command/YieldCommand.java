package me.dontshare.yieldcore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Core's own admin command (/yield reload) - also the reference shape
 * for other plugins' commands: build the tree with
 * Commands.literal(...).requires(...).then(...).executes(...), then hand
 * the finished node to {@link CommandManager#register}.
 */
public final class YieldCommand {

    private YieldCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldCore plugin) {
        return Commands.literal("yield")
                .requires(CommandPermissions.permission("yield.admin"))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadConfig();
                            ctx.getSource().getSender().sendMessage(Text.parse(
                                    "<#4BD9FF><bold>Yield</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Config reloaded.</gray>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(me.dontshare.yieldcore.status.StatusCommand.node())
                .then(Commands.literal("resync")
                        .executes(ctx -> {
                            for (Player player : Bukkit.getOnlinePlayers()) {
                                player.updateCommands();
                            }
                            ctx.getSource().getSender().sendMessage(Text.parse(
                                    "<#4BD9FF><bold>Yield</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Resynced commands for <#FFC64B><count></#FFC64B> online player(s).</gray>",
                                    Placeholder.unparsed("count", String.valueOf(Bukkit.getOnlinePlayers().size()))));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
