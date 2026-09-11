package me.dontshare.yieldcore.home;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;

public final class HomeCommand {

    private static final String DEFAULT_NAME = "home";

    private HomeCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> sethome(HomeService homeService) {
        return Commands.literal("sethome")
                .executes(ctx -> setHome(ctx, homeService, DEFAULT_NAME))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> setHome(ctx, homeService, StringArgumentType.getString(ctx, "name"))))
                .build();
    }

    public static LiteralCommandNode<CommandSourceStack> home(HomeService homeService) {
        return Commands.literal("home")
                .executes(ctx -> teleportHome(ctx, homeService, DEFAULT_NAME))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> teleportHome(ctx, homeService, StringArgumentType.getString(ctx, "name"))))
                .build();
    }

    public static LiteralCommandNode<CommandSourceStack> delhome(HomeService homeService) {
        return Commands.literal("delhome")
                .executes(ctx -> deleteHome(ctx, homeService, DEFAULT_NAME))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> deleteHome(ctx, homeService, StringArgumentType.getString(ctx, "name"))))
                .build();
    }

    public static LiteralCommandNode<CommandSourceStack> homes(HomeService homeService) {
        return Commands.literal("homes")
                .executes(ctx -> {
                    Player player = requirePlayer(ctx);
                    if (player == null) {
                        return Command.SINGLE_SUCCESS;
                    }
                    Map<String, HomePoint> homes = homeService.homesOf(player);
                    if (homes.isEmpty()) {
                        player.sendMessage(Text.parse("<gray>You don't have any homes set - /sethome to make one.</gray>"));
                    } else {
                        player.sendMessage(Text.parse("<#4BD9FF>Your homes (<count>/<max>): <white><names></white></#4BD9FF>",
                                Placeholder.unparsed("count", String.valueOf(homes.size())),
                                Placeholder.unparsed("max", String.valueOf(homeService.getMaxHomes())),
                                Placeholder.unparsed("names", String.join(", ", homes.keySet()))));
                    }
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    private static int setHome(CommandContext<CommandSourceStack> ctx, HomeService homeService, String name) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        HomeService.SetResult result = homeService.setHome(player, name);
        if (result == HomeService.SetResult.SUCCESS) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            player.sendMessage(Text.parse("<green>Home '<name>' set.</green>", Placeholder.unparsed("name", name.toLowerCase(Locale.ROOT))));
        } else {
            player.sendMessage(Text.parse("<red>You're at your home limit (<max>) - /delhome one first.</red>",
                    Placeholder.unparsed("max", String.valueOf(homeService.getMaxHomes()))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int teleportHome(CommandContext<CommandSourceStack> ctx, HomeService homeService, String name) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        Location home = homeService.getHome(player, name);
        if (home == null) {
            player.sendMessage(Text.parse("<red>No home named '<name>' (or its world isn't loaded).</red>",
                    Placeholder.unparsed("name", name.toLowerCase(Locale.ROOT))));
            return Command.SINGLE_SUCCESS;
        }
        player.teleport(home);
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        player.sendMessage(Text.parse("<#4BD9FF>Teleported to '<name>'.</#4BD9FF>", Placeholder.unparsed("name", name.toLowerCase(Locale.ROOT))));
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteHome(CommandContext<CommandSourceStack> ctx, HomeService homeService, String name) {
        Player player = requirePlayer(ctx);
        if (player == null) {
            return Command.SINGLE_SUCCESS;
        }
        if (homeService.deleteHome(player, name)) {
            player.sendMessage(Text.parse("<gray>Home '<name>' deleted.</gray>", Placeholder.unparsed("name", name.toLowerCase(Locale.ROOT))));
        } else {
            player.sendMessage(Text.parse("<red>No home named '<name>'.</red>", Placeholder.unparsed("name", name.toLowerCase(Locale.ROOT))));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static Player requirePlayer(CommandContext<CommandSourceStack> ctx) {
        if (ctx.getSource().getSender() instanceof Player player) {
            return player;
        }
        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
        return null;
    }
}
