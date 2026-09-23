package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.player.SendMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** /sendmode manual|auto - whether equipped pets fight on their own or only when sent (see yield-zones' PetCombatController). */
public final class SendModeCommand {

    private SendModeCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(PlayerDataStore<PackPlayerProfile> store) {
        return Commands.literal("sendmode")
                .then(mode("manual", SendMode.MANUAL, store))
                .then(mode("auto", SendMode.AUTO, store))
                .build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(String literal, SendMode sendMode,
                                                                     PlayerDataStore<PackPlayerProfile> store) {
        return Commands.literal(literal).executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) {
                ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                return Command.SINGLE_SUCCESS;
            }
            // Free for everyone - pets fighting on their own is the
            // baseline now, not a perk (see CombatPerks).
            PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
            profile.setAutoAttack(sendMode == SendMode.AUTO);
            store.save(player.getUniqueId());
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            player.sendMessage(Text.parse("<green>Send mode set to " + literal + ".</green>"));
            return Command.SINGLE_SUCCESS;
        });
    }
}
