package me.dontshare.yieldmining.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldmining.YieldMining;
import me.dontshare.yieldmining.data.MiningSpot;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * "/admin mining give/removespot/list/reload" - "give" hands out the
 * tagged item that creates a packet-only mining spot wherever it's placed
 * (see MiningService#onPlace); "removespot" removes whichever known spot
 * sits closest to the executing admin.
 */
public final class MiningAdminCommand {

    private static final double REMOVE_RADIUS = 5.0;

    private MiningAdminCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(YieldMining plugin) {
        return Commands.literal("mining")
                .then(Commands.literal("give")
                        .then(Commands.argument("material", StringArgumentType.word())
                                .executes(ctx -> give(ctx, plugin, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> give(ctx, plugin, IntegerArgumentType.getInteger(ctx, "amount"))))))
                .then(Commands.literal("removespot").executes(ctx -> removeSpot(ctx, plugin)))
                .then(Commands.literal("list").executes(ctx -> list(ctx, plugin)))
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            plugin.reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-mining content reloaded.</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .build();
    }

    private static int give(CommandContext<CommandSourceStack> ctx, YieldMining plugin, int amount) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.parse("<red>Only a player can be given a mining spot item.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        String raw = StringArgumentType.getString(ctx, "material").toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(raw);
        if (material == null || !plugin.getContent().ores().containsKey(material)) {
            player.sendMessage(Text.parse("<red>'" + raw + "' isn't a configured ore type. Configured: "
                    + plugin.getContent().ores().keySet().stream().map(Enum::name).sorted().reduce((a, b) -> a + ", " + b).orElse("(none)") + "</red>"));
            return Command.SINGLE_SUCCESS;
        }
        ItemStack item = plugin.getMiningItem().create(material);
        item.setAmount(amount);
        player.getInventory().addItem(item);
        player.sendMessage(Text.parse("<green>Gave " + amount + "x mining spot item (" + material.name() + ").</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private static int removeSpot(CommandContext<CommandSourceStack> ctx, YieldMining plugin) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.parse("<red>Only a player can remove a mining spot this way.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        MiningSpot removed = plugin.removeNearestSpot(player.getLocation(), REMOVE_RADIUS);
        if (removed == null) {
            player.sendMessage(Text.parse("<red>No mining spot within " + (int) REMOVE_RADIUS + " blocks of you.</red>"));
        } else {
            player.sendMessage(Text.parse("<green>Removed the " + removed.material().name() + " mining spot at ("
                    + removed.x() + ", " + removed.y() + ", " + removed.z() + ").</green>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> ctx, YieldMining plugin) {
        List<MiningSpot> spots = plugin.getContent().spots();
        if (spots.isEmpty()) {
            ctx.getSource().getSender().sendMessage(Text.parse("<gray>No mining spots placed yet.</gray>"));
            return Command.SINGLE_SUCCESS;
        }
        for (MiningSpot spot : spots) {
            ctx.getSource().getSender().sendMessage(Text.parse("<yellow>" + spot.material().name() + "</yellow> <gray>- "
                    + spot.world().getName() + " (" + spot.x() + ", " + spot.y() + ", " + spot.z() + ")</gray>"));
        }
        return Command.SINGLE_SUCCESS;
    }
}
