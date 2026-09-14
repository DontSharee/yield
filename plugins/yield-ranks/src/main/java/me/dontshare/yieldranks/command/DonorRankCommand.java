package me.dontshare.yieldranks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldranks.DonorRankService;
import me.dontshare.yieldranks.YieldRanks;
import org.bukkit.entity.Player;

/**
 * "/admin ranks apply &lt;player&gt; &lt;id&gt;" - the command a completed
 * store purchase runs as one of its own console commands (see store.yml's
 * "vip_rank"/"celestial_rank" products), plus "/admin ranks reload". The
 * bare, player-facing "/ranks" belongs to yield-packs now (the Rankup GUI -
 * diamonds spent on a prestige rank, not a donor purchase); a donor's own
 * rank and its perks are shown right on the Credits Store's own product
 * listing (see StoreGui/store.yml), so there's no separate self-serve
 * "what donor rank do I have" readout to duplicate here anymore.
 */
public final class DonorRankCommand {

    private DonorRankCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> buildAdminDomain(YieldRanks plugin, DonorRankService service) {
        return Commands.literal("ranks")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-ranks content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("apply")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> apply(ctx, service)))))
                .build();
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, DonorRankService service) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        String rankId = StringArgumentType.getString(ctx, "id");
        service.applyRank(target, rankId);
        return Command.SINGLE_SUCCESS;
    }

}
