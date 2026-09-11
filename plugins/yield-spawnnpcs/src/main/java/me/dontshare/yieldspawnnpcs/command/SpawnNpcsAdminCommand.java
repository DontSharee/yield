package me.dontshare.yieldspawnnpcs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldspawnnpcs.YieldSpawnNpcs;

public final class SpawnNpcsAdminCommand {

    private SpawnNpcsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldSpawnNpcs plugin) {
        return Commands.literal("spawnnpcs")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-spawnnpcs content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
