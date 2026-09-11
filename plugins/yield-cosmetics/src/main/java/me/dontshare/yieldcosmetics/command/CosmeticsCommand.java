package me.dontshare.yieldcosmetics.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldcosmetics.gui.CosmeticsGui;
import org.bukkit.entity.Player;

public final class CosmeticsCommand {

    private CosmeticsCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(CosmeticsGui gui) {
        return Commands.literal("cosmetics")
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
