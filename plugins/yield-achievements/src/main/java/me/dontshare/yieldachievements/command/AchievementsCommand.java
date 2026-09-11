package me.dontshare.yieldachievements.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldachievements.gui.AchievementsGui;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.entity.Player;

public final class AchievementsCommand {

    private AchievementsCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(AchievementsGui gui) {
        return Commands.literal("achievements")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    gui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
