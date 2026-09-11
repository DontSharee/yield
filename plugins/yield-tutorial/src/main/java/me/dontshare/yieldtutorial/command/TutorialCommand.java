package me.dontshare.yieldtutorial.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldtutorial.TutorialService;
import org.bukkit.entity.Player;

public final class TutorialCommand {

    private TutorialCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(TutorialService tutorialService) {
        return Commands.literal("tutorial")
                .then(Commands.literal("skip")
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                                return Command.SINGLE_SUCCESS;
                            }
                            tutorialService.skip(player);
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
