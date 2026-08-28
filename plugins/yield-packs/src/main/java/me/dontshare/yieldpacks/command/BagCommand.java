package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.BagGui;
import org.bukkit.entity.Player;

public final class BagCommand {

    private BagCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(BagGui bagGui) {
        return Commands.literal("bag")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    bagGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
