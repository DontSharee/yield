package me.dontshare.yieldskilltree.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldskilltree.PrestigeService;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.math.BigInteger;
import java.util.List;

/** Prestige confirmation - a Dialog rather than a chest GUI, mirrors yield-rebirth's RebirthDialog. */
public final class PrestigeDialog {

    private static final int BUTTON_WIDTH = 150;

    private final PrestigeService prestigeService;
    private final PlayerDataStore<PackPlayerProfile> playerStore;

    public PrestigeDialog(PrestigeService prestigeService, PlayerDataStore<PackPlayerProfile> playerStore) {
        this.prestigeService = prestigeService;
        this.playerStore = playerStore;
    }

    public void open(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        if (!prestigeService.canPrestige(profile)) {
            int needed = prestigeService.getRebirthsRequired() - profile.getRebirths();
            player.sendMessage(Text.parse("<red>You need <needed> more rebirth(s) to Prestige (requires <required>).</red>",
                    Placeholder.unparsed("needed", String.valueOf(Math.max(0, needed))),
                    Placeholder.unparsed("required", String.valueOf(prestigeService.getRebirthsRequired()))));
            return;
        }

        String message = "&7Prestiging resets your &fCoins&7 and &fRebirths&7 back to &c0&7.\n" +
                "&7Your Bag, equipped pets, and fusions are &anever&7 touched.\n" +
                "&7You'll receive Prestige Points to spend in &d/prestigeupgrades&7.\n\n" +
                "&7Prestige now?";

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.parse("Prestige"))
                        .body(List.of(DialogBody.plainMessage(Text.parse(message))))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Text.parse("Prestige"))
                                .width(BUTTON_WIDTH)
                                .action(DialogAction.customClick((view, audience) -> confirm(player),
                                        ClickCallback.Options.builder().build()))
                                .build(),
                        ActionButton.builder(Text.parse("Cancel")).width(BUTTON_WIDTH).build())));
        player.showDialog(dialog);
    }

    private void confirm(Player player) {
        BigInteger newPoints = prestigeService.prestige(player);
        if (newPoints == null) {
            player.sendMessage(Text.parse("<red>You can't Prestige right now.</red>"));
            return;
        }
        player.sendMessage(Text.parse("<green>Prestiged! You now have <points> Prestige Point(s).</green>",
                Placeholder.unparsed("points", String.valueOf(newPoints))));
    }
}
