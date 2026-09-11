package me.dontshare.yieldzonemachines.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldzonemachines.YieldZoneMachines;

public final class ZoneMachinesAdminCommand {

    private ZoneMachinesAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldZoneMachines plugin) {
        return Commands.literal("zonemachines")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-zonemachines config reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
