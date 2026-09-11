package me.dontshare.yieldskilltree.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldskilltree.YieldSkillTree;

public final class SkillTreeAdminCommand {

    private SkillTreeAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldSkillTree plugin) {
        return Commands.literal("skilltree")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-skilltree content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
