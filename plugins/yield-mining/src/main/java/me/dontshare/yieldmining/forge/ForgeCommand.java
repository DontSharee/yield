package me.dontshare.yieldmining.forge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.entity.Player;

/** "/forge" - opens the Mining Forge. */
public final class ForgeCommand {

    private ForgeCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(ForgeGui forgeGui) {
        return Commands.literal("forge")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    forgeGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
