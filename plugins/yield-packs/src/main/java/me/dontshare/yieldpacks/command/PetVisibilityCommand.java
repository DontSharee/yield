package me.dontshare.yieldpacks.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.display.PetDisplayService;
import me.dontshare.yieldpacks.display.PetVisibility;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import org.bukkit.entity.Player;

/** /petvisibility all|mine|others|none - a per-viewer preference for what equipped-pet displays they personally see. */
public final class PetVisibilityCommand {

    private PetVisibilityCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build(PlayerDataStore<PackPlayerProfile> store,
                                                                PetDisplayService displayService) {
        return Commands.literal("petvisibility")
                .then(mode("all", PetVisibility.ALL, store, displayService))
                .then(mode("mine", PetVisibility.MINE_ONLY, store, displayService))
                .then(mode("others", PetVisibility.OTHERS_ONLY, store, displayService))
                .then(mode("none", PetVisibility.NONE, store, displayService))
                .build();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mode(String literal, PetVisibility visibility,
                                                                     PlayerDataStore<PackPlayerProfile> store,
                                                                     PetDisplayService displayService) {
        return Commands.literal(literal).executes(ctx -> {
            if (!(ctx.getSource().getSender() instanceof Player player)) {
                ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                return Command.SINGLE_SUCCESS;
            }
            PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
            profile.setPetVisibility(visibility);
            store.save(player.getUniqueId());
            displayService.refreshViewer(player);
            player.sendMessage(Text.parse("<green>Pet visibility set to " + literal + ".</green>"));
            return Command.SINGLE_SUCCESS;
        });
    }
}
