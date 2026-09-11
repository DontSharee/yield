package me.dontshare.yieldteams.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldteams.YieldTeams;

public final class TeamsAdminCommand {

    private TeamsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldTeams plugin) {
        return Commands.literal("teams")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-teams content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
