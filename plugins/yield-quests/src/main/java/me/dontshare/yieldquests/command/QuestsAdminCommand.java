package me.dontshare.yieldquests.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldquests.QuestService;
import me.dontshare.yieldquests.YieldQuests;
import me.dontshare.yieldquests.data.QuestDefinition;
import me.dontshare.yieldquests.data.QuestDifficulty;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class QuestsAdminCommand {

    private QuestsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldQuests plugin, QuestService questService) {
        return Commands.literal("quests")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-quests content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .then(Commands.argument("difficulty", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
                                                    Player target = resolver.resolve(ctx.getSource()).getFirst();
                                                    String category = StringArgumentType.getString(ctx, "category");
                                                    String difficultyRaw = StringArgumentType.getString(ctx, "difficulty").toUpperCase(Locale.ROOT);
                                                    QuestDifficulty difficulty;
                                                    try {
                                                        difficulty = QuestDifficulty.valueOf(difficultyRaw);
                                                    } catch (IllegalArgumentException e) {
                                                        ctx.getSource().getSender().sendMessage(Text.parse("<red>Invalid difficulty '" + difficultyRaw + "' - expected EASY, MEDIUM, or HARD.</red>"));
                                                        return Command.SINGLE_SUCCESS;
                                                    }
                                                    QuestDefinition quest = questService.forceComplete(target, category, difficulty);
                                                    if (quest == null) {
                                                        ctx.getSource().getSender().sendMessage(Text.parse("<red>No such category/difficulty, or " + target.getName() + " already claimed it today.</red>"));
                                                    } else {
                                                        ctx.getSource().getSender().sendMessage(Text.parse("<green>Completed and claimed '" + category + "' (" + difficulty + ") for " + target.getName() + ".</green>"));
                                                    }
                                                    return Command.SINGLE_SUCCESS;
                                                })))))
                .build();
    }
}
