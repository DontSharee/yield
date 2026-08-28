package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.command.CommandPermissions;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

/** Admin/testing commands - no real economy grind needed to exercise purchasing, rolling, or resetting. */
public final class PacksAdminCommand {

    private PacksAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldPacks plugin) {
        return Commands.literal("packsadmin")
                .requires(CommandPermissions.permission("yieldpacks.admin"))
                .then(Commands.literal("give-coins")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> giveCoins(ctx, plugin)))))
                .then(Commands.literal("give-gems")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                        .executes(ctx -> giveGems(ctx, plugin)))))
                .then(Commands.literal("give-item")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("pack", StringArgumentType.word())
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> giveItem(ctx, plugin)))))))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-packs content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reset")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(ctx -> reset(ctx, plugin))))
                .build();
    }

    private static int giveCoins(CommandContext<CommandSourceStack> ctx, YieldPacks plugin) throws CommandSyntaxException {
        Player target = resolveTarget(ctx);
        long amount = LongArgumentType.getLong(ctx, "amount");
        PackPlayerProfile profile = plugin.getPlayerStore().getOrCreate(target.getUniqueId());
        profile.setCoins(profile.getCoins() + amount);
        plugin.getPlayerStore().save(target.getUniqueId());
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Gave " + amount + " coins to " + target.getName() + ".</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int giveGems(CommandContext<CommandSourceStack> ctx, YieldPacks plugin) throws CommandSyntaxException {
        Player target = resolveTarget(ctx);
        long amount = LongArgumentType.getLong(ctx, "amount");
        PackPlayerProfile profile = plugin.getPlayerStore().getOrCreate(target.getUniqueId());
        profile.setGems(profile.getGems() + amount);
        plugin.getPlayerStore().save(target.getUniqueId());
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Gave " + amount + " gems to " + target.getName() + ".</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int giveItem(CommandContext<CommandSourceStack> ctx, YieldPacks plugin) throws CommandSyntaxException {
        Player target = resolveTarget(ctx);
        String packId = StringArgumentType.getString(ctx, "pack");
        String itemId = StringArgumentType.getString(ctx, "item");
        int count = IntegerArgumentType.getInteger(ctx, "count");
        PackPlayerProfile profile = plugin.getPlayerStore().getOrCreate(target.getUniqueId());
        for (int i = 0; i < count; i++) {
            profile.addOwnedItem(packId, itemId);
            plugin.getEquipmentService().autoEquipOnRoll(profile, itemId);
        }
        plugin.getPlayerStore().save(target.getUniqueId());
        plugin.getPetDisplayService().refresh(target);
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Gave " + count + "x " + itemId + " to " + target.getName() + ".</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, YieldPacks plugin) throws CommandSyntaxException {
        Player target = resolveTarget(ctx);
        PackPlayerProfile profile = plugin.getPlayerStore().getOrCreate(target.getUniqueId());
        profile.setCoins(0);
        profile.setGems(0);
        profile.setRebirths(0);
        profile.setAutoOpenEnabled(false);
        profile.getOwnedItems().clear();
        profile.getEquippedItemIds().clear();
        profile.getPackCollectionProgress().clear();
        profile.getLastObtainedAt().clear();
        plugin.getPlayerStore().save(target.getUniqueId());
        plugin.getPetDisplayService().refresh(target);
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Reset pack data for " + target.getName() + ".</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static Player resolveTarget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        return resolver.resolve(ctx.getSource()).getFirst();
    }
}
