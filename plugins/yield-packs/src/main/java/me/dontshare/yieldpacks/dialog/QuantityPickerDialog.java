package me.dontshare.yieldpacks.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackRollService;
import me.dontshare.yieldpacks.roll.RollAnimationService;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Buy 1/5/25/200/1K/10K/Max quantity picker - a Dialog rather than a
 * second chest GUI, since it's a short fixed menu that fires an action
 * immediately with no grid to lay out.
 */
public final class QuantityPickerDialog {

    private static final int[] FIXED_QUANTITIES = {1, 5, 25, 200, 1_000, 10_000};
    private static final int MAX_HARD_CAP = 1_000_000;
    private static final int BUTTON_WIDTH = 150;

    private final PackRollService rollService;
    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final RollAnimationService animationService;

    public QuantityPickerDialog(PackRollService rollService, PlayerDataStore<PackPlayerProfile> playerStore,
                                 RollAnimationService animationService) {
        this.rollService = rollService;
        this.playerStore = playerStore;
        this.animationService = animationService;
    }

    public void open(Player player, PackDefinition pack) {
        List<ActionButton> buttons = new ArrayList<>();
        for (int quantity : FIXED_QUANTITIES) {
            buttons.add(button("Buy " + Formatting.format(quantity), () -> attempt(player, pack, quantity)));
        }
        buttons.add(button("Buy Max", () -> attempt(player, pack, rollService.maxAffordable(player, pack.id(), MAX_HARD_CAP))));

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.parse(pack.displayName()))
                        .body(List.of(DialogBody.plainMessage(Text.parse(
                                "&7Cost per open: &a$" + Formatting.spaced(pack.coinCost()) + " &8| &e" + pack.gemCost() + " gems"))))
                        .build())
                .type(DialogType.multiAction(buttons, cancelButton(), 2)));
        player.showDialog(dialog);
    }

    private void attempt(Player player, PackDefinition pack, int quantity) {
        PackRollService.PurchaseResult result = rollService.buyAndOpen(player, pack.id(), quantity);
        if (!result.success()) {
            player.sendMessage(Text.parse("<red><reason></red>", Placeholder.unparsed("reason", result.failureReason())));
            return;
        }

        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        if (profile.isRollAnimationEnabled()) {
            animationService.play(player, result.rolls());
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PackRollService.RollResult roll : result.rolls()) {
            counts.merge(roll.item().displayName(), 1, Integer::sum);
        }
        String summaryList = String.join(", ", counts.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey())
                .toList());
        player.sendMessage(Text.parse(
                "<#4BD9FF><bold>Packs</bold></#4BD9FF> <dark_gray>»</dark_gray> <gray>Opened <count>x <pack>: <results></gray>",
                Placeholder.unparsed("count", String.valueOf(result.rolls().size())),
                Placeholder.unparsed("pack", pack.displayName()),
                Placeholder.unparsed("results", summaryList)));
    }

    private ActionButton button(String label, Runnable onClick) {
        return ActionButton.builder(Text.parse(label))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick((view, audience) -> onClick.run(), ClickCallback.Options.builder().build()))
                .build();
    }

    private ActionButton cancelButton() {
        return ActionButton.builder(Text.parse("Cancel")).width(BUTTON_WIDTH).build();
    }
}
