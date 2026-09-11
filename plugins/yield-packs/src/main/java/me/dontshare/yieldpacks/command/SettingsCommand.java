package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.gui.SettingsGui;
import org.bukkit.entity.Player;

public final class SettingsCommand {

    private SettingsCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(SettingsGui settingsGui) {
        return Commands.literal("settings")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    settingsGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
