package me.dontshare.yieldcore.teleport;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class TpaCommand {

    private TpaCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> tpa(TeleportRequestService service) {
        return Commands.literal("tpa")
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(ctx -> request(ctx, service, false)))
                .build();
    }

    public static LiteralCommandNode<CommandSourceStack> tpahere(TeleportRequestService service) {
        return Commands.literal("tpahere")
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(ctx -> request(ctx, service, true)))
                .build();
    }

    public static LiteralCommandNode<CommandSourceStack> tpaccept(TeleportRequestService service) {
        return Commands.literal("tpaccept")
                .executes(ctx -> {
                    Player player = requirePlayer(ctx);
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (!service.accept(player)) {
                        player.sendMessage(Text.parse("<red>You don't have a pending teleport request.</red>"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    public static LiteralCommandNode<CommandSourceStack> tpdeny(TeleportRequestService service) {
        return Commands.literal("tpdeny")
                .executes(ctx -> {
                    Player player = requirePlayer(ctx);
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    if (service.deny(player)) {
                        player.sendMessage(Text.parse("<gray>Request denied.</gray>"));
                    } else {
                        player.sendMessage(Text.parse("<red>You don't have a pending teleport request.</red>"));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static int request(CommandContext<CommandSourceStack> ctx, TeleportRequestService service, boolean here) throws CommandSyntaxException {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        if (target.equals(player)) {
            player.sendMessage(Text.parse("<red>You can't teleport request yourself.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        service.request(player, target, here);
        player.sendMessage(Text.parse("<green>Teleport request sent to <name>.</green>", Placeholder.unparsed("name", target.getName())));
        target.playSound(target.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
        String verb = here ? "wants you to teleport to them" : "wants to teleport to you";
        target.sendMessage(Text.parse("<#4BD9FF><name></#4BD9FF> <gray><verb> - /tpaccept or /tpdeny</gray>",
                Placeholder.unparsed("name", player.getName()), Placeholder.unparsed("verb", verb)));
        return Command.SINGLE_SUCCESS;
    }

    private static Player requirePlayer(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) {
            return player;
        }
        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
        return null;
    }
}
