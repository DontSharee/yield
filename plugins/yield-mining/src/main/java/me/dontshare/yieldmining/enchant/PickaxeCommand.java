package me.dontshare.yieldmining.enchant;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.entity.Player;

/** "/pickaxe" - the same menu right-clicking a pickaxe opens, for players who'd rather type it. */
public final class PickaxeCommand {

    private PickaxeCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(PickaxeUpgradeGui upgradeGui) {
        return Commands.literal("pickaxe")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    upgradeGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
