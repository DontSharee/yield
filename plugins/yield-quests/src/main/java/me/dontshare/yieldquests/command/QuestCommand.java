package me.dontshare.yieldquests.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldquests.gui.QuestGui;
import org.bukkit.entity.Player;

public final class QuestCommand {

    private QuestCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(QuestGui questGui) {
        return Commands.literal("quests")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    questGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
