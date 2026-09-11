package me.dontshare.yieldleveling.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldleveling.YieldLeveling;

public final class LevelingAdminCommand {

    private LevelingAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldLeveling plugin) {
        return Commands.literal("leveling")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-leveling config reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
