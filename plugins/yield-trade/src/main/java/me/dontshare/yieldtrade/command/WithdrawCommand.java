package me.dontshare.yieldtrade.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldtrade.currency.CurrencyNoteService;
import me.dontshare.yieldtrade.currency.TradeCurrency;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.Optional;

/** {@code /withdraw <coins|diamonds|credits> <amount>} - turns a balance into a tradeable note. */
public final class WithdrawCommand {

    private WithdrawCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(CurrencyNoteService noteService) {
        var command = Commands.literal("withdraw");
        for (TradeCurrency currency : TradeCurrency.values()) {
            command.then(Commands.literal(currency.id())
                    .then(Commands.argument("amount", StringArgumentType.word())
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(Text.parse("<red>Only players can withdraw.</red>"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                String raw = StringArgumentType.getString(ctx, "amount");
                                Optional<BigInteger> amount = parseAmount(raw);
                                if (amount.isEmpty()) {
                                    player.sendMessage(Text.parse("<red>Couldn't read \"" + raw + "\" as an amount.</red>"));
                                    return Command.SINGLE_SUCCESS;
                                }
                                noteService.withdraw(player, currency, amount.get());
                                return Command.SINGLE_SUCCESS;
                            })));
        }
        return command.build();
    }

    /** Accepts both plain digits and the abbreviated forms the UI shows ("1.5M"). */
    private static Optional<BigInteger> parseAmount(String raw) {
        try {
            return Optional.of(new BigInteger(raw.replace(",", "").trim()));
        } catch (NumberFormatException ignored) {
            try {
                BigInteger parsed = Formatting.unformatToBigInteger(raw.trim());
                return parsed == null ? Optional.empty() : Optional.of(parsed);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
    }
}
