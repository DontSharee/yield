package me.dontshare.yieldevents.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldevents.EventQuestGui;
import me.dontshare.yieldevents.EventService;
import me.dontshare.yieldevents.data.SeasonalEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.time.LocalDate;

/** "/event" - what is running, how long is left, and what you are holding. */
public final class EventCommand {

    private EventCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(EventService eventService, EventQuestGui questGui) {
        return Commands.literal("event")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    SeasonalEvent event = eventService.active();
                    if (event == null) {
                        player.sendMessage(Text.parse("<gray>No event is running right now. Check back soon.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    // The quest screen says everything the chat summary did
                    // and shows the quests too, so the command just opens it.
                    questGui.open(player);
                    long days = event.daysRemaining(LocalDate.now());
                    player.sendMessage(Text.parse(
                            "<" + event.color() + "><bold><name></bold></" + event.color() + ">"
                                    + " <gray>-</gray> <white><days></white> <gray>day<plural> left</gray>",
                            Placeholder.unparsed("name", event.displayName()),
                            Placeholder.unparsed("days", String.valueOf(days)),
                            Placeholder.unparsed("plural", days == 1 ? "" : "s")));
                    player.sendMessage(Text.parse(
                            "<gray>You have</gray> <white><held></white> <gray><currency>."
                                    + " The event egg costs</gray> <white><price></white><gray>.</gray>",
                            Placeholder.unparsed("held", Formatting.format((double) eventService.balance(player, event))),
                            Placeholder.unparsed("currency", event.currencyName()),
                            Placeholder.unparsed("price", Formatting.format((double) event.eggPrice()))));
                    player.sendMessage(Text.parse(
                            "<gray>Break cubes to earn <currency>, then hatch at the event egg by spawn.</gray>",
                            Placeholder.unparsed("currency", event.currencyName())));
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }
}
