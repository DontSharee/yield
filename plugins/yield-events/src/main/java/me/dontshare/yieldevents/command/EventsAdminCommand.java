package me.dontshare.yieldevents.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldevents.YieldEvents;

public final class EventsAdminCommand {

    private EventsAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldEvents plugin) {
        return Commands.literal("events")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-events config reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }
}
