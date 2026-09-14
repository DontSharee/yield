package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.store.StoreHubGui;
import org.bukkit.entity.Player;

/** "/store" - opens {@link StoreHubGui}, the single landing screen for every purchase/progression destination (Rankup, the Credits Store, Crates, the Pack Shop). */
public final class StoreCommand {

    private StoreCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(StoreHubGui storeHubGui) {
        return Commands.literal("store")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    storeHubGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
