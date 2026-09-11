package me.dontshare.yieldteams.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldteams.gui.TeamsMenuGui;
import org.bukkit.entity.Player;

public final class TeamsMenuCommand {

    private TeamsMenuCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(TeamsMenuGui teamsMenuGui) {
        return Commands.literal("teams")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    teamsMenuGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
