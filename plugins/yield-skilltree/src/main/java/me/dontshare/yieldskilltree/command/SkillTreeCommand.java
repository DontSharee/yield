package me.dontshare.yieldskilltree.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldskilltree.gui.SkillTreeGui;
import org.bukkit.entity.Player;

public final class SkillTreeCommand {

    private SkillTreeCommand() {
    }

    /** Builds a thin command literal that just opens {@code treeId} in the shared SkillTreeGui - used for both /upgrades and /prestigeupgrades. */
    public static LiteralCommandNode<CommandSourceStack> build(String literal, String treeId, SkillTreeGui gui) {
        return Commands.literal(literal)
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    gui.open(player, treeId);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
