package me.dontshare.yieldrebirth.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldrebirth.dialog.RebirthDialog;
import org.bukkit.entity.Player;

public final class RebirthCommand {

    private RebirthCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(RebirthDialog rebirthDialog) {
        return Commands.literal("rebirth")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    rebirthDialog.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
