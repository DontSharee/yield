package me.dontshare.yieldpacks.command;

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
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.enchant.EnchantType;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Pack-system-wide admin actions that don't fit any one stat/pet - granting
 * currency/pets/roll-count all moved to "/admin stats" and "/admin pets"
 * instead (see those commands), so this is reloading config, the full-wipe
 * "reset", and "give" (a direct Enchant Book grant - the only admin-side
 * way to test Enchants without grinding pack RNG for a drop).
 */
public final class PacksAdminCommand {

    private PacksAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldPacks plugin) {
        return Commands.literal("packs")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-packs content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("reset")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(ctx -> reset(ctx, plugin))))
                .then(Commands.literal("give")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .then(Commands.argument("rarity", StringArgumentType.word())
                                                .executes(ctx -> giveEnchant(ctx, plugin))))))
                .build();
    }

    /** "/admin packs give <player> <type> <rarity>" - grants one Enchant Book directly, bypassing the drop roll (see EnchantService#giveBook) - the only way to test premium/rebirth-unlocked slots without grinding pack RNG. */
    private static int giveEnchant(CommandContext<CommandSourceStack> ctx, YieldPacks plugin) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        String typeArg = StringArgumentType.getString(ctx, "type");
        String rarityArg = StringArgumentType.getString(ctx, "rarity");

        EnchantType type;
        try {
            type = EnchantType.valueOf(typeArg.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Unknown enchant type '" + typeArg
                    + "'. Valid: coins, diamonds, damage, attack_speed, luck</red>"));
            return Command.SINGLE_SUCCESS;
        }
        Rarity rarity = plugin.getRarityRegistry().find(rarityArg.toLowerCase(Locale.ROOT)).orElse(null);
        if (rarity == null) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Unknown rarity '" + rarityArg + "'.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        plugin.getEnchantService().giveBook(target, type, rarity);
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Gave " + target.getName() + " a "
                + rarity.id() + " " + type.name() + " Enchant Book.</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, YieldPacks plugin) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        PackPlayerProfile profile = plugin.getPlayerStore().getOrCreate(target.getUniqueId());
        profile.setCoins(BigInteger.ZERO);
        profile.setDiamonds(BigInteger.ZERO);
        profile.setRebirths(0);
        profile.setAutoOpenEnabled(false);
        profile.clearPets();
        profile.getEquippedPetIds().clear();
        profile.getPackCollectionProgress().clear();
        // Legacy, and only still read by StoredEggRefundListener - clearing
        // it here stops a reset player being handed a refund on their next
        // join for eggs the reset just took away.
        profile.getStoredPacks().clear();
        profile.getLastObtainedAt().clear();
        plugin.getPlayerStore().save(target.getUniqueId());
        plugin.getPetDisplayService().refresh(target);
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Reset pack data for " + target.getName() + ".</green>"));
        return Command.SINGLE_SUCCESS;
    }
}
