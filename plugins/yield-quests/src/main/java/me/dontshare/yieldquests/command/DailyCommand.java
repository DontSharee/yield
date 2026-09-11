package me.dontshare.yieldquests.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldquests.gui.PresentsGui;
import org.bukkit.entity.Player;

public final class DailyCommand {

    private DailyCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(PresentsGui presentsGui) {
        return Commands.literal("daily")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    presentsGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
