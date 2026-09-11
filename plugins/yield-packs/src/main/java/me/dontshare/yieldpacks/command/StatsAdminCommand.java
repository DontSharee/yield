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
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Locale;

/**
 * "/admin stats set|add &lt;player&gt; &lt;stat&gt; &lt;amount&gt;" - one
 * command covering every stat on {@link PackPlayerProfile} an admin might
 * ever need to adjust for player support, rather than a separate give-X
 * command per currency. "set" replaces the value outright; "add" (which
 * also accepts a negative amount, i.e. subtracts) adjusts it relative to
 * whatever it currently is.
 */
public final class StatsAdminCommand {

    private enum Stat {
        COINS, GEMS, CREDITS, REBIRTHS, PRESTIGES, PRESTIGE_POINTS, ROLL_COUNT, CUBE_KILLS, COINS_EARNED, BOSS_DAMAGE, LUCK
    }

    private StatsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldPacks plugin) {
        return Commands.literal("stats")
                .then(Commands.literal("set")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("stat", StringArgumentType.word())
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(ctx -> apply(ctx, plugin, true))))))
                .then(Commands.literal("add")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("stat", StringArgumentType.word())
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(ctx -> apply(ctx, plugin, false))))))
                .build();
    }

    private static int apply(CommandContext<CommandSourceStack> ctx, YieldPacks plugin, boolean set) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        String rawStat = StringArgumentType.getString(ctx, "stat").toUpperCase(Locale.ROOT);
        String rawAmount = StringArgumentType.getString(ctx, "amount");

        Stat stat;
        try {
            stat = Stat.valueOf(rawStat);
        } catch (IllegalArgumentException e) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Unknown stat '" + rawStat + "' - expected one of: "
                    + String.join(", ", java.util.Arrays.stream(Stat.values()).map(Enum::name).toArray(String[]::new)) + "</red>"));
            return Command.SINGLE_SUCCESS;
        }

        PackPlayerProfile profile = plugin.getPlayerStore().getOrCreate(target.getUniqueId());
        String resultLine;
        if (stat == Stat.LUCK) {
            double amount = parseDouble(ctx, rawAmount);
            double newValue = set ? amount : profile.getAdminLuckBonus() + amount;
            profile.setAdminLuckBonus(newValue);
            resultLine = "+" + Formatting.format(newValue) + " luck bonus";
        } else if (isBigInteger(stat)) {
            BigInteger amount = parseBigInteger(ctx, rawAmount);
            if (amount == null) {
                return Command.SINGLE_SUCCESS;
            }
            BigInteger newValue = applyBigInteger(profile, stat, amount, set);
            resultLine = Formatting.format(newValue) + " " + stat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        } else {
            long amount = parseLong(ctx, rawAmount);
            long newValue = applyLong(profile, stat, amount, set);
            resultLine = Formatting.format((double) newValue) + " " + stat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        plugin.getPlayerStore().save(target.getUniqueId());
        ctx.getSource().getSender().sendMessage(Text.parse("<green>" + target.getName() + "'s " + stat.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + " is now <white>" + resultLine + "</white>.</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static boolean isBigInteger(Stat stat) {
        return stat == Stat.COINS || stat == Stat.GEMS || stat == Stat.CREDITS || stat == Stat.PRESTIGE_POINTS || stat == Stat.COINS_EARNED;
    }

    private static BigInteger applyBigInteger(PackPlayerProfile profile, Stat stat, BigInteger amount, boolean set) {
        BigInteger current = switch (stat) {
            case COINS -> profile.getCoins();
            case GEMS -> profile.getGems();
            case CREDITS -> profile.getCredits();
            case PRESTIGE_POINTS -> profile.getPrestigePoints();
            case COINS_EARNED -> profile.getLifetimeCoinsEarned();
            default -> BigInteger.ZERO;
        };
        BigInteger updated = set ? amount : current.add(amount);
        if (updated.signum() < 0) {
            updated = BigInteger.ZERO;
        }
        switch (stat) {
            case COINS -> profile.setCoins(updated);
            case GEMS -> profile.setGems(updated);
            case CREDITS -> profile.setCredits(updated);
            case PRESTIGE_POINTS -> profile.setPrestigePoints(updated);
            case COINS_EARNED -> profile.setLifetimeCoinsEarned(updated);
            default -> {
            }
        }
        return updated;
    }

    private static long applyLong(PackPlayerProfile profile, Stat stat, long amount, boolean set) {
        long current = switch (stat) {
            case REBIRTHS -> profile.getRebirths();
            case PRESTIGES -> profile.getPrestiges();
            case ROLL_COUNT -> profile.getRollCount();
            case CUBE_KILLS -> profile.getLifetimeCubeKills();
            case BOSS_DAMAGE -> profile.getLifetimeBossDamage();
            default -> 0L;
        };
        long updated = Math.max(0, set ? amount : current + amount);
        switch (stat) {
            case REBIRTHS -> profile.setRebirths((int) updated);
            case PRESTIGES -> profile.setPrestiges((int) updated);
            case ROLL_COUNT -> profile.setRollCount(updated);
            case CUBE_KILLS -> profile.setLifetimeCubeKills(updated);
            case BOSS_DAMAGE -> profile.setLifetimeBossDamage(updated);
            default -> {
            }
        }
        return updated;
    }

    /** Accepts both a plain number and shorthand like "1.5b"/"10m"/"250k" - see {@link Formatting#unformatToBigInteger}. */
    private static BigInteger parseBigInteger(CommandContext<CommandSourceStack> ctx, String raw) {
        try {
            return Formatting.unformatToBigInteger(raw);
        } catch (NumberFormatException e) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Invalid amount: '" + raw + "'.</red>"));
            return null;
        }
    }

    private static long parseLong(CommandContext<CommandSourceStack> ctx, String raw) {
        try {
            BigInteger value = Formatting.unformatToBigInteger(raw);
            BigInteger clamped = value.max(BigInteger.valueOf(Long.MIN_VALUE)).min(BigInteger.valueOf(Long.MAX_VALUE));
            return clamped.longValueExact();
        } catch (NumberFormatException e) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Invalid amount: '" + raw + "'.</red>"));
            return 0;
        }
    }

    private static double parseDouble(CommandContext<CommandSourceStack> ctx, String raw) {
        try {
            return Formatting.unformat(raw);
        } catch (NumberFormatException e) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>Invalid amount: '" + raw + "'.</red>"));
            return 0;
        }
    }
}
