package me.dontshare.yieldcore.spawn;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.command.CommandPermissions;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class SpawnCommand {

    private SpawnCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> spawn(SpawnService spawnService) {
        return Commands.literal("spawn")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    player.teleport(spawnService.get());
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    player.sendMessage(Text.parse("<#4BD9FF>Teleported to spawn.</#4BD9FF>"));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    public static LiteralCommandNode<CommandSourceStack> setSpawn(SpawnService spawnService) {
        return Commands.literal("setspawn")
                .requires(CommandPermissions.permission("yield.admin.setspawn"))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Console must teleport to a location first - run this in-game.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    spawnService.set(player.getLocation());
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                    player.sendMessage(Text.parse("<green>Spawn set to your current location.</green>"));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
