package me.dontshare.yieldupgrades.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldupgrades.YieldUpgrades;

public final class UpgradesAdminCommand {

    private UpgradesAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldUpgrades plugin) {
        return Commands.literal("upgrades")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-upgrades config reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
