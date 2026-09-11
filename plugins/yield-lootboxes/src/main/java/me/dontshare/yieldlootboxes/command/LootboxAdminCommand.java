package me.dontshare.yieldlootboxes.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldlootboxes.LootboxService;
import me.dontshare.yieldlootboxes.YieldLootboxes;
import org.bukkit.entity.Player;

/**
 * "/admin lootbox give &lt;player&gt; &lt;box&gt; &lt;amount&gt;" - the
 * literal command a Tebex package gets configured to run on a confirmed
 * real-money purchase (e.g. "lootbox give %player_name% mythic_crate 1" via
 * Tebex's own placeholder syntax), plus a config reload.
 */
public final class LootboxAdminCommand {

    private LootboxAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldLootboxes plugin, LootboxService service) {
        return Commands.literal("lootbox")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-lootboxes content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("give")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("box", StringArgumentType.word())
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(ctx -> give(ctx, service))))))
                .build();
    }

    private static int give(CommandContext<CommandSourceStack> ctx, LootboxService service) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        String boxId = StringArgumentType.getString(ctx, "box");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        if (service.give(target, boxId, amount)) {
            ctx.getSource().getSender().sendMessage(Text.parse("<green>Gave " + target.getName() + " " + amount + "x '" + boxId + "'.</green>"));
        } else {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>No such lootbox '" + boxId + "'.</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }
}
