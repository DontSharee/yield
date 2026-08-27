package me.dontshare.yield.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Thin wrapper around Paper's native Brigadier command registration
 * ({@code io.papermc.paper.command.brigadier}). Build a command tree with
 * {@code Commands.literal(...).then(...).executes(...)} as normal (Paper's
 * own builder is already fluent - no need to wrap it in another DSL), then
 * call {@link #register} instead of dealing with
 * {@code LifecycleEventManager}/{@code LifecycleEvents.COMMANDS} directly.
 * <p>
 * Registration is tied to the <b>owning</b> plugin's own lifecycle, not
 * core's - each plugin's {@code LifecycleEventManager} fires its COMMANDS
 * event based on that specific plugin's own load timing. Always pass the
 * plugin that actually owns the command, even though this helper lives in
 * core - registering everything centrally through core's own lifecycle
 * would miss commands from plugins that finish enabling after core already
 * fired its COMMANDS event.
 */
public final class CommandManager {

    private CommandManager() {
    }

    public static void register(JavaPlugin owner, LiteralCommandNode<CommandSourceStack> node) {
        register(owner, node, null, List.of());
    }

    public static void register(JavaPlugin owner, LiteralCommandNode<CommandSourceStack> node, String description) {
        register(owner, node, description, List.of());
    }

    public static void register(JavaPlugin owner, LiteralCommandNode<CommandSourceStack> node,
                                 String description, List<String> aliases) {
        owner.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(node, description, aliases));
    }
}
