package me.dontshare.yieldpacks.mastery;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.entity.Player;

/** "/masteries" - opens {@link MasteryGui}, the readout of all 4 passive XP tracks. */
public final class MasteryCommand {

    private MasteryCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(MasteryGui gui) {
        return Commands.literal("masteries")
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
