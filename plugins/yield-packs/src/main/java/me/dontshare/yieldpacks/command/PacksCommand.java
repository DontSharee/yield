package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.PackShopGui;
import org.bukkit.entity.Player;

public final class PacksCommand {

    private PacksCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(PackShopGui packShopGui) {
        return Commands.literal("merchant")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    packShopGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
