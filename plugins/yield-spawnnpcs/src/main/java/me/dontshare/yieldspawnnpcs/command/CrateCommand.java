package me.dontshare.yieldspawnnpcs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldspawnnpcs.crate.CrateGui;
import org.bukkit.entity.Player;

/** "/crates" - opens the Crate Shop. Meant to be run via a FancyNpcs "PLAYER_COMMAND" right-click action on an in-game-placed NPC (see this plugin's own README note) - no custom NPC display of our own needed. */
public final class CrateCommand {

    private CrateCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(CrateGui crateGui) {
        return Commands.literal("crates")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    crateGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
