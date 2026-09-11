package me.dontshare.yieldcosmetics.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldcosmetics.YieldCosmetics;

public final class CosmeticsAdminCommand {

    private CosmeticsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldCosmetics plugin) {
        return Commands.literal("cosmetics")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-cosmetics content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
