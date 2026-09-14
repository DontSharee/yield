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
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldranks.DonorRankService;
import me.dontshare.yieldranks.YieldRanks;
import me.dontshare.yieldranks.data.DonorRank;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Optional;

/**
 * "/admin ranks apply &lt;player&gt; &lt;id&gt;" - the command a completed
 * store purchase runs as one of its own console commands (see store.yml's
 * "vip_rank"/"celestial_rank" products), plus "/admin ranks reload".
 * "/ranks" (no args, player-only) is the separate self-serve "what do I
 * currently have" info command.
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

    public static LiteralCommandNode<CommandSourceStack> buildInfo(DonorRankService service, PlayerDataStore<PackPlayerProfile> store) {
        return Commands.literal("ranks")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
                    Optional<DonorRank> rank = service.currentRank(profile);
                    if (rank.isEmpty()) {
                        player.sendMessage(Text.parse("<gray>You have no donor rank. Check /buy!</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    DonorRank r = rank.get();
                    player.sendMessage(Text.parse("<gold><bold>" + r.displayName().toUpperCase(Locale.ROOT) + "</bold></gold>"));
                    player.sendMessage(Text.parse("<gray>" + r.coinMultiplier() + "x Money, " + r.diamondMultiplier() + "x Diamond, "
                            + r.xpMultiplier() + "x Exp, +" + r.bonusPetSlots() + " Pet Slot(s)</gray>"));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
