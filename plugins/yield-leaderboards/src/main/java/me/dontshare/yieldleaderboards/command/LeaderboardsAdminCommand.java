package me.dontshare.yieldleaderboards.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldleaderboards.YieldLeaderboards;

public final class LeaderboardsAdminCommand {

    private LeaderboardsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldLeaderboards plugin) {
        return Commands.literal("leaderboards")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-leaderboards content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("refresh")
                        .executes(ctx -> {
                            plugin.getLeaderboardService().refreshAll();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>Refreshing all leaderboards now.</green>"));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("leaderboard", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestLeaderboardIds(plugin, builder))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "leaderboard");
                                    boolean found = plugin.getLeaderboardService().refreshOne(id);
                                    if (found) {
                                        ctx.getSource().getSender().sendMessage(Text.parse("<green>Refreshing leaderboard '" + id + "' now.</green>"));
                                    } else {
                                        ctx.getSource().getSender().sendMessage(Text.parse("<red>No leaderboard named '" + id + "' is configured.</red>"));
                                    }
                                    return Command.SINGLE_SUCCESS;
                                })))
                .build();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestLeaderboardIds(
            YieldLeaderboards plugin, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String id : plugin.getContent().leaderboards().keySet()) {
            if (id.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }
}
