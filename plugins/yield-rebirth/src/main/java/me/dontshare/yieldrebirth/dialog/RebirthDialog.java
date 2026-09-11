package me.dontshare.yieldrebirth.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldrebirth.RebirthService;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.List;

/** Rebirth confirmation - a Dialog rather than a chest GUI, since it's a single yes/no decision with a text summary. */
public final class RebirthDialog {

    private static final int BUTTON_WIDTH = 150;

    private final RebirthService rebirthService;
    private final PlayerDataStore<PackPlayerProfile> playerStore;

    public RebirthDialog(RebirthService rebirthService, PlayerDataStore<PackPlayerProfile> playerStore) {
        this.rebirthService = rebirthService;
        this.playerStore = playerStore;
    }

    public void open(Player player) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        RebirthService.RebirthPreview preview = rebirthService.preview(profile);

        if (preview.available() <= 0) {
            player.sendMessage(Text.parse("<red>You can't afford a rebirth yet. Next one costs $<cost>.</red>",
                    Placeholder.unparsed("cost", Formatting.format(rebirthService.requiredCoins(profile.getRebirths())))));
            return;
        }

        String message = "&7Rebirth &f" + preview.available() + " &7time(s) now for &a$" +
                Formatting.format(preview.totalCost()) + "&7?\n" +
                "&7Current Boost: &a+" + boostPercent(rebirthService.coinMultiplier(profile)) + "%\n" +
                "&7Boost After: &a+" + boostPercent(rebirthService.coinMultiplierForRebirths(profile.getRebirths() + preview.available())) + "%";

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.parse("Rebirth"))
                        .body(List.of(DialogBody.plainMessage(Text.parse(message))))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Text.parse("Rebirth"))
                                .width(BUTTON_WIDTH)
                                .action(DialogAction.customClick((view, audience) -> confirm(player),
                                        ClickCallback.Options.builder().build()))
                                .build(),
                        ActionButton.builder(Text.parse("Cancel")).width(BUTTON_WIDTH).build())));
        player.showDialog(dialog);
    }

    private void confirm(Player player) {
        RebirthService.RebirthPreview applied = rebirthService.rebirth(player);
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        player.sendMessage(Text.parse("<green>Rebirthed <count> time(s)! New boost: +<boost>%</green>",
                Placeholder.unparsed("count", String.valueOf(applied.available())),
                Placeholder.unparsed("boost", boostPercent(rebirthService.coinMultiplier(profile)))));
    }

    /** "5" not "5.0" for a whole-number percent, "12.5" for a fractional one - {@code coin-bonus-per-rebirth} isn't guaranteed to divide evenly. */
    private String boostPercent(double multiplier) {
        return Formatting.format((multiplier - 1.0) * 100);
    }
}
