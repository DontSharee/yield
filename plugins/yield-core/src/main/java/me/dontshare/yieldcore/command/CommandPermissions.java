package me.dontshare.yieldcore.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.function.Predicate;

/** Readable permission-check helper for use with Brigadier's {@code .requires(...)}. */
public final class CommandPermissions {

    private CommandPermissions() {
    }

    public static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }
}
