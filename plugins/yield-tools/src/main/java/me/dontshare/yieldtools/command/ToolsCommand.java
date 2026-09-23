package me.dontshare.yieldtools.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldtools.gui.ToolsGui;
import org.bukkit.entity.Player;

/** "/tools" - opens {@link ToolsGui}. */
public final class ToolsCommand {

    private ToolsCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(ToolsGui gui) {
        return Commands.literal("tools")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    gui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
