package me.dontshare.yieldrebirth.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldrebirth.YieldRebirth;

public final class RebirthAdminCommand {

    private RebirthAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldRebirth plugin) {
        return Commands.literal("rebirth")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadRebirthConfig();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-rebirth config reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
