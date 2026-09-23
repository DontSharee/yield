package me.dontshare.yieldtools.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldtools.ToolService;
import me.dontshare.yieldtools.YieldTools;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/**
 * "/admin tools reload" re-reads tools.yml; "/admin tools set &lt;player&gt;
 * &lt;n&gt;" puts a player at tool n on the path (1 for the first, 0 for
 * none) - the only way to test the late path without grinding for it.
 */
public final class ToolsAdminCommand {

    private ToolsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldTools plugin, ToolService tools) {
        return Commands.literal("tools")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse(
                                    "<green>yield-tools reloaded - <count> tools on the path.</green>",
                                    Placeholder.unparsed("count", String.valueOf(tools.all().size()))));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("set")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("tool", IntegerArgumentType.integer(0))
                                        .executes(ctx -> {
                                            Player target = ctx.getArgument("target", PlayerSelectorArgumentResolver.class)
                                                    .resolve(ctx.getSource()).getFirst();
                                            int tool = IntegerArgumentType.getInteger(ctx, "tool");
                                            tools.setOwned(target, tool - 1);
                                            ctx.getSource().getSender().sendMessage(Text.parse(
                                                    "<green><player> now owns the first <n> tool(s).</green>",
                                                    Placeholder.unparsed("player", target.getName()),
                                                    Placeholder.unparsed("n", String.valueOf(Math.min(tool, tools.all().size())))));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                .build();
    }
}
