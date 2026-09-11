package me.dontshare.yieldblocktree.command;

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
import me.dontshare.yieldblocktree.YieldBlockTree;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/** "/admin blocktree reload/give/reset" - config reload plus support/testing shortcuts for granting or wiping one player's progress on a specific block. */
public final class BlockTreeAdminCommand {

    private BlockTreeAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldBlockTree plugin) {
        return Commands.literal("blocktree")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-blocktree content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("give")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("material", StringArgumentType.word())
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(BlockTreeAdminCommand::give)))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("material", StringArgumentType.word())
                                        .executes(BlockTreeAdminCommand::reset))))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("material", StringArgumentType.word())
                                        .then(Commands.argument("tier", IntegerArgumentType.integer(1))
                                                .executes(ctx -> unlock(ctx, plugin))))))
                .build();
    }

    private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = resolveTarget(ctx);
        Material material = resolveMaterial(ctx);
        if (material == null) {
            return Command.SINGLE_SUCCESS;
        }
        long amount;
        try {
            amount = Formatting.unformatToBigInteger(StringArgumentType.getString(ctx, "amount")).longValueExact();
        } catch (Exception e) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Invalid amount.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(target.getUniqueId());
        profile.getBlockTreeProgress().merge(material.name(), amount, Long::sum);
        packs.getPlayerStore().save(target.getUniqueId());
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Gave " + target.getName() + " +" + amount + " " + material.name() + " breaks.</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Player target = resolveTarget(ctx);
        Material material = resolveMaterial(ctx);
        if (material == null) {
            return Command.SINGLE_SUCCESS;
        }
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(target.getUniqueId());
        profile.getBlockTreeProgress().remove(material.name());
        profile.getClaimedBlockTreeTiers().removeIf(k -> k.startsWith(material.name() + ":"));
        packs.getPlayerStore().save(target.getUniqueId());
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Reset " + target.getName() + "'s " + material.name() + " blocktree progress.</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int unlock(CommandContext<CommandSourceStack> ctx, YieldBlockTree plugin) throws CommandSyntaxException {
        Player target = resolveTarget(ctx);
        Material material = resolveMaterial(ctx);
        if (material == null) {
            return Command.SINGLE_SUCCESS;
        }
        // Players see tiers numbered from 1 (see BlockTreeCategoryGui) - the service itself is 0-indexed.
        int tierIndex = IntegerArgumentType.getInteger(ctx, "tier") - 1;
        var result = plugin.getBlockTreeService().forceClaim(target, material, tierIndex);
        switch (result) {
            case SUCCESS -> ctx.getSource().getSender().sendMessage(Text.parse("<green>Unlocked tier " + (tierIndex + 1) + " of '" + material.name() + "' for " + target.getName() + ".</green>"));
            case ALREADY_CLAIMED -> ctx.getSource().getSender().sendMessage(Text.parse("<red>" + target.getName() + " already claimed that tier.</red>"));
            default -> ctx.getSource().getSender().sendMessage(Text.parse("<red>No such block/tier '" + material.name() + "' " + (tierIndex + 1) + ".</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Player resolveTarget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        return resolver.resolve(ctx.getSource()).getFirst();
    }

    private static Material resolveMaterial(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "material").toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Unknown material '" + raw + "'.</red>"));
        }
        return material;
    }
}
