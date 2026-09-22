package me.dontshare.yieldpacks.enchant;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.entity.Player;

/** "/enchantmarket" - opens {@link EnchantMarketGui}. */
public final class EnchantMarketCommand {

    private EnchantMarketCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(EnchantMarketGui gui) {
        return Commands.literal("enchantmarket")
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
