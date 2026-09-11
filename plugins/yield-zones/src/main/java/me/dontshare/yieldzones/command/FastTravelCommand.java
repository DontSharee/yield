package me.dontshare.yieldzones.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldzones.gui.FastTravelGui;
import org.bukkit.entity.Player;

public final class FastTravelCommand {

    private FastTravelCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(FastTravelGui fastTravelGui) {
        return Commands.literal("fasttravel")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    fastTravelGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
