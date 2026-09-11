package me.dontshare.yieldpacks.command;

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
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * "/admin pets give &lt;player&gt; &lt;pet&gt; &lt;fusion&gt; &lt;amount&gt;" -
 * builds the real item id from a base pet id + fusion tier (e.g. "stray_cat"
 * + GOLDEN -&gt; "stray_cat_golden") so an admin never has to know the
 * concatenated id format, same idea as potions' "give" command. Always adds
 * to storage only - never auto-equips - so it can never bump something the
 * player already has equipped for a reason.
 */
public final class PetsAdminCommand {

    private PetsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldPacks plugin) {
        return Commands.literal("pets")
                .then(Commands.literal("give")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("pet", StringArgumentType.word())
                                        .then(Commands.argument("fusion", StringArgumentType.word())
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> give(ctx, plugin)))))))
                .build();
    }

    private static int give(CommandContext<CommandSourceStack> ctx, YieldPacks plugin) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        String basePetId = StringArgumentType.getString(ctx, "pet");
        String fusionRaw = StringArgumentType.getString(ctx, "fusion").toUpperCase(Locale.ROOT);
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        FusionTier tier;
        try {
            tier = FusionTier.valueOf(fusionRaw);
        } catch (IllegalArgumentException e) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Unknown fusion tier '" + fusionRaw + "' - expected NORMAL, GOLDEN, RAINBOW, or DARK_MATTER.</red>"));
            return Command.SINGLE_SUCCESS;
        }

        String itemId = tier.idFor(basePetId);
        if (plugin.getItemRegistry().find(itemId).isEmpty()) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>No such pet '" + itemId + "' - check the base pet id and that this fusion tier exists for it.</red>"));
            return Command.SINGLE_SUCCESS;
        }

        PackPlayerProfile profile = plugin.getPlayerStore().getOrCreate(target.getUniqueId());
        for (int i = 0; i < amount; i++) {
            profile.addOwnedItem("admin", itemId);
        }
        plugin.getPlayerStore().save(target.getUniqueId());
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Gave " + target.getName() + " " + amount + "x " + itemId + " (in storage).</green>"));
        return Command.SINGLE_SUCCESS;
    }
}
