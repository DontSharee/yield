package me.dontshare.yieldauctionhouse.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldauctionhouse.AuctionService;
import me.dontshare.yieldauctionhouse.data.AuctionCurrency;
import me.dontshare.yieldauctionhouse.gui.AuctionBrowseGui;
import me.dontshare.yieldcore.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigInteger;
import java.util.Locale;

/** "/auction" (alias "/ah") opens the browse screen; "/auction sell <price> [currency]" lists whatever's in your hand - the only way to list something, per design (no in-GUI sell flow). */
public final class AuctionCommand {

    private AuctionCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(JavaPlugin plugin, AuctionBrowseGui browseGui, AuctionService service) {
        return Commands.literal("auction")
                .executes(ctx -> open(ctx, browseGui))
                .then(Commands.literal("sell")
                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                .executes(ctx -> sell(ctx, plugin, service, "coins"))
                                .then(Commands.argument("currency", StringArgumentType.word())
                                        .executes(ctx -> sell(ctx, plugin, service, StringArgumentType.getString(ctx, "currency"))))))
                .build();
    }

    private static int open(CommandContext<CommandSourceStack> ctx, AuctionBrowseGui browseGui) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
            return Command.SINGLE_SUCCESS;
        }
        browseGui.open(player);
        return Command.SINGLE_SUCCESS;
    }

    private static int sell(CommandContext<CommandSourceStack> ctx, JavaPlugin plugin, AuctionService service, String currencyRaw) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
            return Command.SINGLE_SUCCESS;
        }
        AuctionCurrency currency;
        try {
            currency = AuctionCurrency.valueOf(currencyRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            player.sendMessage(Text.parse("<red>Unknown currency '" + currencyRaw + "' - expected coins, diamonds, or credits.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        if (service.isBusy(player)) {
            player.sendMessage(Text.parse("<red>Hold on, your last action is still processing.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        BigInteger price = BigInteger.valueOf(LongArgumentType.getLong(ctx, "price"));
        service.listHeldItem(player, currency, price).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            switch (result) {
                case SUCCESS -> player.sendMessage(Text.parse("<green><bold>Listed!</bold></green> <gray>Check /auction -> My Listings.</gray>"));
                case PRICE_TOO_LOW -> player.sendMessage(Text.parse("<red>Price is below the minimum.</red>"));
                case NOTHING_HELD -> player.sendMessage(Text.parse("<red>You're not holding anything to sell.</red>"));
                case ITEM_BOUND -> player.sendMessage(Text.parse("<red>You can't sell that.</red>"));
                case LISTING_CAP_REACHED -> player.sendMessage(Text.parse("<red>You've hit your active-listing limit.</red>"));
                case BUSY -> player.sendMessage(Text.parse("<red>Hold on, your last action is still processing.</red>"));
                case FAILED -> player.sendMessage(Text.parse("<red>Something went wrong - try again.</red>"));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }
}
