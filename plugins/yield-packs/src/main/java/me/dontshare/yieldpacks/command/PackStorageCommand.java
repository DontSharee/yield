package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.EggCatalogGui;
import org.bukkit.entity.Player;

public final class PackStorageCommand {

    private PackStorageCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(EggCatalogGui eggCatalogGui) {
        return Commands.literal("packs")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    eggCatalogGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
