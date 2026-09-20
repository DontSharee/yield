package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

/** Toggles the in-world roll reveal on pack opens - off falls back to the action-bar reel and, for a multi-open, the chest-grid summary (see PackRevealAnimationService / PackMultiOpenResultGui). */
public final class RollAnimationCommand {

    private RollAnimationCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(PlayerDataStore<PackPlayerProfile> store) {
        return Commands.literal("rollanimation")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
                    boolean enabled = !profile.isRollAnimationEnabled();
                    profile.setRollAnimationEnabled(enabled);
                    store.save(player.getUniqueId());
                    player.sendMessage(Text.parse(enabled
                            ? "<green>Roll animation enabled.</green>"
                            : "<gray>Roll animation disabled.</gray>"));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
