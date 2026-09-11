package me.dontshare.yieldcore.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A single shared "/admin &lt;domain&gt; ..." command tree every plugin
 * contributes one branch to, instead of each minting its own top-level
 * "*admin" command (packsadmin, zonesadmin, ...) - the scattered layout this
 * replaces.
 * <p>
 * Call {@link #register} with your own domain's subtree (e.g. {@code
 * Commands.literal("packs").then(...)}) from your plugin's {@code onEnable}
 * - same timing as any other cross-plugin registration in this codebase.
 * The actual {@code /admin} command itself is only assembled and registered
 * once, from yield-core's own {@code LifecycleEvents.COMMANDS} handler
 * (see {@code YieldCore}) - by the time that fires, every plugin loaded at
 * server startup has already run its own {@code onEnable} and registered
 * whatever domain it owns, the same "everyone registers during onEnable,
 * one plugin assembles the result once startup is fully done" shape already
 * used elsewhere in this codebase (e.g. yield-zones deferring its own
 * content load a tick to let every world-creating plugin finish first).
 */
public final class AdminCommandRegistry {

    private final List<LiteralCommandNode<CommandSourceStack>> domains = new ArrayList<>();

    public void register(LiteralCommandNode<CommandSourceStack> domain) {
        domains.add(domain);
    }

    public List<LiteralCommandNode<CommandSourceStack>> domains() {
        return domains;
    }
}
