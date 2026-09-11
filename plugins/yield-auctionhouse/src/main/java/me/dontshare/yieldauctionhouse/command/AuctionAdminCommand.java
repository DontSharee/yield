package me.dontshare.yieldauctionhouse.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldauctionhouse.AuctionService;
import me.dontshare.yieldauctionhouse.YieldAuctionHouse;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.entity.Player;

import java.util.UUID;

/** "/admin auction cancel <player> <listingId>" (support override) + "/admin auction reload". */
public final class AuctionAdminCommand {

    private AuctionAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldAuctionHouse plugin, AuctionService service) {
        return Commands.literal("auction")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-auctionhouse config reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("listingId", StringArgumentType.word())
                                        .executes(ctx -> cancel(ctx, service)))))
                .build();
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx, AuctionService service) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        UUID listingId;
        try {
            listingId = UUID.fromString(StringArgumentType.getString(ctx, "listingId"));
        } catch (IllegalArgumentException e) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Invalid listing id.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        service.cancel(target, listingId).thenAccept(result ->
                ctx.getSource().getSender().sendMessage(Text.parse("<green>Result: " + result + "</green>")));
        return Command.SINGLE_SUCCESS;
    }
}
