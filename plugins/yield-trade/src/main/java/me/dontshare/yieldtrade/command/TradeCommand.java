package me.dontshare.yieldtrade.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldtrade.session.TradeService;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

/** {@code /trade <player>}, {@code /trade accept <player>}, {@code /trade deny <player>}. */
public final class TradeCommand {

    private TradeCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(TradeService tradeService) {
        return Commands.literal("trade")
                .then(Commands.literal("accept")
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .executes(ctx -> {
                                    Player target = resolve(ctx.getSource(), ctx.getArgument("player", PlayerSelectorArgumentResolver.class));
                                    if (!(ctx.getSource().getSender() instanceof Player accepter)) {
                                        return playersOnly(ctx.getSource());
                                    }
                                    accept(tradeService, accepter, target);
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("deny")
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .executes(ctx -> {
                                    Player target = resolve(ctx.getSource(), ctx.getArgument("player", PlayerSelectorArgumentResolver.class));
                                    if (!(ctx.getSource().getSender() instanceof Player denier)) {
                                        return playersOnly(ctx.getSource());
                                    }
                                    tradeService.clearRequest(denier.getUniqueId(), target.getUniqueId());
                                    denier.sendMessage(Text.parse("<gray>Trade request denied.</gray>"));
                                    target.sendMessage(Text.parse("<red><name> denied your trade request.</red>",
                                            Placeholder.unparsed("name", denier.getName())));
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(ctx -> {
                            Player target = resolve(ctx.getSource(), ctx.getArgument("player", PlayerSelectorArgumentResolver.class));
                            if (!(ctx.getSource().getSender() instanceof Player requester)) {
                                return playersOnly(ctx.getSource());
                            }
                            request(tradeService, requester, target);
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private static void request(TradeService tradeService, Player requester, Player target) {
        if (target == null) {
            requester.sendMessage(Text.parse("<red>That player isn't online.</red>"));
            return;
        }
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            requester.sendMessage(Text.parse("<red>You can't trade with yourself.</red>"));
            return;
        }
        if (!eligible(tradeService, requester, requester) || !partnerEligible(tradeService, requester, target)) {
            return;
        }
        if (tradeService.isTrading(requester.getUniqueId())) {
            requester.sendMessage(Text.parse("<red>You're already in a trade.</red>"));
            return;
        }
        if (tradeService.isTrading(target.getUniqueId())) {
            requester.sendMessage(Text.parse("<red>They're already in a trade.</red>"));
            return;
        }
        tradeService.sendRequest(requester, target);
    }

    private static void accept(TradeService tradeService, Player accepter, Player requester) {
        if (requester == null) {
            accepter.sendMessage(Text.parse("<red>That player isn't online.</red>"));
            return;
        }
        if (!tradeService.hasPendingRequest(accepter.getUniqueId(), requester.getUniqueId())) {
            accepter.sendMessage(Text.parse("<red>No trade request from them (it may have expired).</red>"));
            return;
        }
        if (!eligible(tradeService, accepter, accepter) || !partnerEligible(tradeService, accepter, requester)) {
            return;
        }
        // Re-checked at accept time, not just when the request was sent - either
        // player could have started a different trade in between.
        if (tradeService.isTrading(accepter.getUniqueId())) {
            accepter.sendMessage(Text.parse("<red>You're already in a trade.</red>"));
            return;
        }
        if (tradeService.isTrading(requester.getUniqueId())) {
            accepter.sendMessage(Text.parse("<red>They've already started another trade.</red>"));
            return;
        }
        tradeService.clearRequest(accepter.getUniqueId(), requester.getUniqueId());
        tradeService.open(requester, accepter);
    }

    private static boolean eligible(TradeService tradeService, Player messageTo, Player subject) {
        int missing = tradeService.rebirthsShort(subject.getUniqueId());
        if (missing > 0) {
            messageTo.sendMessage(Text.parse(
                    "<red>Trading unlocks at Rebirth <required></red><gray> - you need <missing> more.</gray>",
                    Placeholder.unparsed("required", String.valueOf(TradeService.REQUIRED_REBIRTHS)),
                    Placeholder.unparsed("missing", String.valueOf(missing))));
            return false;
        }
        return true;
    }

    private static boolean partnerEligible(TradeService tradeService, Player messageTo, Player partner) {
        if (tradeService.rebirthsShort(partner.getUniqueId()) > 0) {
            messageTo.sendMessage(Text.parse(
                    "<red><name> hasn't unlocked trading yet</red><gray> (Rebirth <required>).</gray>",
                    Placeholder.unparsed("name", partner.getName()),
                    Placeholder.unparsed("required", String.valueOf(TradeService.REQUIRED_REBIRTHS))));
            return false;
        }
        return true;
    }

    private static Player resolve(CommandSourceStack source, PlayerSelectorArgumentResolver resolver) {
        try {
            return resolver.resolve(source).getFirst();
        } catch (Exception e) {
            return null;
        }
    }

    private static int playersOnly(CommandSourceStack source) {
        source.getSender().sendMessage(Text.parse("<red>Only players can trade.</red>"));
        return Command.SINGLE_SUCCESS;
    }
}
