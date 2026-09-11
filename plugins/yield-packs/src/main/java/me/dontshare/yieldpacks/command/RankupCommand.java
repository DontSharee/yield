package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.RankupGui;
import org.bukkit.entity.Player;

/** "/rankup" - opens {@link RankupGui}. Replaces the old walk-in trigger (removed per design - Rankup is now reachable anywhere, plus an NPC at spawn is meant to run this same command). */
public final class RankupCommand {

    private RankupCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(RankupGui rankupGui) {
        return Commands.literal("rankup")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    rankupGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
