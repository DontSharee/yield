package me.dontshare.yieldcore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Staff quality-of-life commands: /gmc, /gms, /gmsp, /gma, /fly. */
public final class AdminCommands {

    private AdminCommands() {
    }

    public static LiteralCommandNode<CommandSourceStack> gamemode(String label, GameMode mode) {
        return Commands.literal(label)
                .requires(CommandPermissions.permission("yield.admin.gamemode"))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Console must specify a target player.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    applyGamemode(player, player, mode);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(ctx -> {
                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
                            Player target = resolver.resolve(ctx.getSource()).getFirst();
                            applyGamemode(ctx.getSource().getSender(), target, mode);
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    public static LiteralCommandNode<CommandSourceStack> fly() {
        return Commands.literal("fly")
                .requires(CommandPermissions.permission("yield.admin.fly"))
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Console must specify a target player.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    toggleFly(player);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(ctx -> {
                            PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
                            Player target = resolver.resolve(ctx.getSource()).getFirst();
                            toggleFly(target);
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private static void applyGamemode(org.bukkit.command.CommandSender sender, Player target, GameMode mode) {
        target.setGameMode(mode);
        target.sendMessage(Text.parse(
                "<#4BD9FF><bold>Yield</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Your gamemode was set to <#FFC64B><mode></#FFC64B>.</gray>",
                Placeholder.unparsed("mode", mode.name())));

        if (sender != target) {
            sender.sendMessage(Text.parse(
                    "<#4BD9FF><bold>Yield</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Set <#FFC64B><target></#FFC64B>'s gamemode to <#FFC64B><mode></#FFC64B>.</gray>",
                    Placeholder.unparsed("target", target.getName()),
                    Placeholder.unparsed("mode", mode.name())));
        }
    }

    private static void toggleFly(Player player) {
        boolean enabling = !player.getAllowFlight();
        player.setAllowFlight(enabling);
        player.setFlying(enabling);

        String stateTag = enabling ? "<#55FF7F>enabled" : "<#FF4B4B>disabled";
        player.sendMessage(Text.parse(
                "<#4BD9FF><bold>Yield</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Flight <state></gray>.",
                Placeholder.parsed("state", stateTag)));
    }
}
