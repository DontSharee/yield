package me.dontshare.yieldachievements.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.store.StoreHubGui;
import org.bukkit.entity.Player;

/** "/buy" - opens {@link StoreHubGui}, same as "/store" (yield-packs) - the Buycraft/Tebex-style storefront, tabbed by Ranks/Gamepasses/Bundles/Exclusive Crates (all registered from this plugin's own onEnable, since it owns the Credits Store). */
public final class BuyCommand {

    private BuyCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(StoreHubGui gui) {
        return Commands.literal("buy")
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
