package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.AutoTargetMode;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** /autotarget closest|strongest|weakest - which live cube idle pets pick on their own (only used in Auto send mode - see /sendmode). */
public final class AutoTargetCommand {

    private AutoTargetCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(PlayerDataStore<PackPlayerProfile> store) {
        return Commands.literal("autotarget")
                .then(mode("closest", AutoTargetMode.CLOSEST, store))
                .then(mode("strongest", AutoTargetMode.STRONGEST, store))
                .then(mode("weakest", AutoTargetMode.WEAKEST, store))
                .build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(String literal, AutoTargetMode autoTargetMode,
                                                                     PlayerDataStore<PackPlayerProfile> store) {
        return Commands.literal(literal).executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) {
                ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                return Command.SINGLE_SUCCESS;
            }
            PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
            profile.setAutoTargetMode(autoTargetMode);
            store.save(player.getUniqueId());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            player.sendMessage(Text.parse("<green>Auto-target set to " + literal + ".</green>"));
            return Command.SINGLE_SUCCESS;
        });
    }
}
