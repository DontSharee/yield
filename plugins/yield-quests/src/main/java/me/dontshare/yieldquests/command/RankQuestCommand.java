package me.dontshare.yieldquests.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldquests.gui.RankQuestGui;
import org.bukkit.entity.Player;

public final class RankQuestCommand {

    private RankQuestCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(RankQuestGui rankQuestGui) {
        return Commands.literal("rankquests")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    rankQuestGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
