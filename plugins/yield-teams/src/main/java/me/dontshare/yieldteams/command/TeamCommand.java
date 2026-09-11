package me.dontshare.yieldteams.command;

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
import me.dontshare.yieldteams.TeamService;
import me.dontshare.yieldteams.gui.TeamGui;
import me.dontshare.yieldteams.gui.TeamUpgradeGui;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public final class TeamCommand {

    private TeamCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(TeamService teamService, TeamGui teamGui, TeamUpgradeGui upgradeGui) {
        return Commands.literal("team")
                .executes(ctx -> openInfo(ctx, teamGui))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> create(ctx, teamService))))
                .then(Commands.literal("invite")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(ctx -> invite(ctx, teamService))))
                .then(Commands.literal("accept")
                        .executes(ctx -> accept(ctx, teamService)))
                .then(Commands.literal("leave")
                        .executes(ctx -> leave(ctx, teamService)))
                .then(Commands.literal("kick")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(ctx -> kick(ctx, teamService))))
                .then(Commands.literal("disband")
                        .executes(ctx -> disband(ctx, teamService)))
                .then(Commands.literal("info")
                        .executes(ctx -> openInfo(ctx, teamGui)))
                .then(Commands.literal("upgrades")
                        .executes(ctx -> openUpgrades(ctx, upgradeGui)))
                .build();
    }

    private static int create(CommandContext<CommandSourceStack> ctx, TeamService teamService) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        String name = StringArgumentType.getString(ctx, "name");
        TeamService.CreateResult result = teamService.create(player, name);
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                player.sendMessage(Text.parse("<green>Team '<name>' created!</green>", Placeholder.unparsed("name", name)));
            }
            case ALREADY_IN_TEAM -> player.sendMessage(Text.parse("<red>You're already in a team - /team leave first.</red>"));
            case NAME_TAKEN -> player.sendMessage(Text.parse("<red>That team name is already taken.</red>"));
            case INVALID_LENGTH -> player.sendMessage(Text.parse("<red>That name's length isn't allowed.</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int invite(CommandContext<CommandSourceStack> ctx, TeamService teamService) throws CommandSyntaxException {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player target = resolveTarget(ctx);
        TeamService.InviteResult result = teamService.invite(player, target);
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(Text.parse("<green>Invited <name>.</green>", Placeholder.unparsed("name", target.getName())));
                target.sendMessage(Text.parse("<#4BD9FF><name></#4BD9FF> <gray>invited you to their team - /team accept</gray>",
                        Placeholder.unparsed("name", player.getName())));
                target.playSound(target.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.2f);
            }
            case NOT_IN_TEAM -> player.sendMessage(Text.parse("<red>You're not in a team.</red>"));
            case NOT_LEADER -> player.sendMessage(Text.parse("<red>Only the team leader can invite.</red>"));
            case TARGET_IS_SELF -> player.sendMessage(Text.parse("<red>You can't invite yourself.</red>"));
            case TARGET_ALREADY_IN_TEAM -> player.sendMessage(Text.parse("<red>That player is already in a team.</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int accept(CommandContext<CommandSourceStack> ctx, TeamService teamService) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        TeamService.JoinResult result = teamService.acceptInvite(player);
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                player.sendMessage(Text.parse("<green>Joined the team!</green>"));
            }
            case NO_PENDING_INVITE -> player.sendMessage(Text.parse("<red>You don't have a pending team invite.</red>"));
            case ALREADY_IN_TEAM -> player.sendMessage(Text.parse("<red>You're already in a team.</red>"));
            case TEAM_GONE -> player.sendMessage(Text.parse("<red>That team no longer exists.</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx, TeamService teamService) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        TeamService.LeaveResult result = teamService.leave(player);
        switch (result) {
            case SUCCESS -> player.sendMessage(Text.parse("<gray>You left the team.</gray>"));
            case DISBANDED -> player.sendMessage(Text.parse("<gray>You left the team - it had no members left, so it was disbanded.</gray>"));
            case NOT_IN_TEAM -> player.sendMessage(Text.parse("<red>You're not in a team.</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int kick(CommandContext<CommandSourceStack> ctx, TeamService teamService) throws CommandSyntaxException {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Player target = resolveTarget(ctx);
        TeamService.KickResult result = teamService.kick(player, target.getUniqueId());
        switch (result) {
            case SUCCESS -> {
                player.sendMessage(Text.parse("<green>Kicked <name>.</green>", Placeholder.unparsed("name", target.getName())));
                target.sendMessage(Text.parse("<red>You were kicked from your team.</red>"));
            }
            case NOT_LEADER -> player.sendMessage(Text.parse("<red>Only the team leader can kick.</red>"));
            case CANNOT_KICK_SELF -> player.sendMessage(Text.parse("<red>Use /team leave or /team disband instead.</red>"));
            case TARGET_NOT_IN_TEAM -> player.sendMessage(Text.parse("<red>That player isn't in your team.</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int disband(CommandContext<CommandSourceStack> ctx, TeamService teamService) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        TeamService.DisbandResult result = teamService.disband(player);
        switch (result) {
            case SUCCESS -> player.sendMessage(Text.parse("<gray>Team disbanded.</gray>"));
            case NOT_LEADER -> player.sendMessage(Text.parse("<red>Only the team leader can disband.</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int openInfo(CommandContext<CommandSourceStack> ctx, TeamGui teamGui) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        teamGui.open(player);
        return Command.SINGLE_SUCCESS;
    }

    private static int openUpgrades(CommandContext<CommandSourceStack> ctx, TeamUpgradeGui upgradeGui) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        upgradeGui.open(player);
        return Command.SINGLE_SUCCESS;
    }

    private static Player requirePlayer(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) {
            return player;
        }
        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
        return null;
    }

    private static Player resolveTarget(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        return resolver.resolve(ctx.getSource()).getFirst();
    }
}
