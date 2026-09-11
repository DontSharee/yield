package me.dontshare.yieldpackstations.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpackstations.YieldPackStations;

public final class PackStationsAdminCommand {

    private PackStationsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldPackStations plugin) {
        return Commands.literal("packstations")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-packstations config reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
