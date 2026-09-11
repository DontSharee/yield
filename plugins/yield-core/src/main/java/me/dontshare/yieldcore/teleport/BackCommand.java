package me.dontshare.yieldcore.teleport;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class BackCommand {

    private BackCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(BackLocationService service) {
        return Commands.literal("back")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    if (service.teleportBack(player)) {
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                        player.sendMessage(Text.parse("<#4BD9FF>Teleported back.</#4BD9FF>"));
                    } else {
                        player.sendMessage(Text.parse("<red>Nowhere to go back to yet.</red>"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
