package me.dontshare.yieldskilltree.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldskilltree.dialog.PrestigeDialog;
import org.bukkit.entity.Player;

public final class PrestigeCommand {

    private PrestigeCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(PrestigeDialog prestigeDialog) {
        return Commands.literal("prestige")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    prestigeDialog.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
