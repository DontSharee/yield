package me.dontshare.yieldpacks.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.gui.PackOddsLore;
import me.dontshare.yieldpacks.gui.PackStorageGui;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackOpenService;
import me.dontshare.yieldpacks.roll.PackRollService;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Replaces the old bulk QuantityPickerDialog - a stored pack is opened from
 * a fixed ladder of quantities ({@link #MULTI_OPEN_TIERS}) rather than a
 * free-form quantity prompt, matching the old dialog's removal reasoning.
 * "Open 1x" is always there; the bigger tiers need the Multi-Open gamepass
 * ({@link PackOpenService#MULTI_OPEN_PERMISSION}) and only show once you
 * actually have that many stored, so the dialog never offers an open it
 * would then have to refuse. There's also a toggle for auto-opening this
 * pack, which repeats a 1x open on a cooldown until storage runs out - see
 * PackOpenService.
 * <p>
 * Every button here hands off to {@link PackOpenService}, which owns the
 * reveal: the results are shown in the world in front of the player (one
 * reel for a 1x, a grid of the whole haul for the bigger tiers), so a
 * successful open deliberately does NOT re-open this dialog - it would
 * cover the very thing the player clicked to see. Only a REFUSED open
 * (cooldown, empty storage) brings the dialog back.
 */
public final class OpenPackDialog {

    private static final int BUTTON_WIDTH = 150;
    /**
     * The quantities offered, in order. 1 is ungated; the rest need the
     * gamepass. Kept well under {@link PackRollService#MULTI_OPEN_CAP} (24)
     * so the whole ladder is always openable in one action, and small
     * enough at the top end that 15 results still fit the in-world reveal
     * grid legibly.
     */
    private static final int[] MULTI_OPEN_TIERS = {3, 5, 15};

    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final PackOpenService openService;
    private final PackOddsLore oddsLore;
    private PackStorageGui packStorageGui;

    public OpenPackDialog(PlayerDataStore<PackPlayerProfile> playerStore, PackOpenService openService,
                           PackOddsLore oddsLore) {
        this.playerStore = playerStore;
        this.openService = openService;
        this.oddsLore = oddsLore;
    }

    /** Breaks the constructor cycle with {@link PackStorageGui} (which itself needs this class for its pack-click handlers) - same setter-injection idiom as MilestoneCategoryGui/MilestonesGui. Lets both exit paths (Close, or opening your last stored pack) land back on a freshly-rebuilt storage screen instead of a stale one. */
    public void setPackStorageGui(PackStorageGui packStorageGui) {
        this.packStorageGui = packStorageGui;
    }

    private void returnToStorage(Player player) {
        if (packStorageGui != null) {
            packStorageGui.open(player);
        } else {
            player.closeDialog();
        }
    }

    public void open(Player player, PackDefinition pack) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        profile.setActivePackId(pack.id());

        boolean autoOpenOn = profile.isAutoOpenEnabled();
        int stored = profile.getStoredPacks().getOrDefault(pack.id(), 0);

        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(Text.parse("Open 1x"))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick((view, audience) -> {
                            if (!openService.tryOpen(player, pack.id(), PackOpenService.OpenTrigger.DIALOG)) {
                                // Refused - still on cooldown, or that was
                                // the last pack and something else claimed
                                // it. Nothing is being revealed, so put the
                                // dialog back rather than leaving the
                                // player on an empty screen.
                                open(player, pack);
                            }
                        },
                        ClickCallback.Options.builder().build()))
                .build());

        boolean multiOpen = player.hasPermission(PackOpenService.MULTI_OPEN_PERMISSION);
        for (int tier : MULTI_OPEN_TIERS) {
            if (!multiOpen || stored < tier) {
                continue;
            }
            int count = tier;
            buttons.add(ActionButton.builder(Text.parse("Open " + count + "x"))
                    .width(BUTTON_WIDTH)
                    .action(DialogAction.customClick((view, audience) -> {
                                if (!openService.tryOpenMany(player, pack.id(), count).success()) {
                                    open(player, pack);
                                }
                            },
                            ClickCallback.Options.builder().build()))
                    .build());
        }

        buttons.add(ActionButton.builder(Text.parse(autoOpenOn ? "Disable Auto-Open" : "Enable Auto-Open"))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick((view, audience) -> toggleAutoOpen(player, pack),
                        ClickCallback.Options.builder().build()))
                .build());

        // Stored count first, then this player's own luck and the chase
        // odds it moves - the numbers that decide whether opening now or
        // buying a luck potion first is the better move, on the screen
        // where that decision is made.
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Text.parse("&7Stored: &f" + stored)));
        for (String line : oddsLore.chaseLines(pack, player)) {
            body.add(DialogBody.plainMessage(Text.parse(line)));
        }

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.parse(pack.displayName()))
                        .body(body)
                        .build())
                .type(DialogType.multiAction(buttons, cancelButton(player), 2)));
        player.showDialog(dialog);
    }

    private void toggleAutoOpen(Player player, PackDefinition pack) {
        PackPlayerProfile profile = playerStore.getOrCreate(player.getUniqueId());
        profile.setActivePackId(pack.id());
        boolean enabled = !profile.isAutoOpenEnabled();
        profile.setAutoOpenEnabled(enabled);
        player.sendMessage(Text.parse(enabled
                ? "<green>Auto-opening " + Formatting.stripLeadingColorCodes(pack.displayName()) + " until you run out.</green>"
                : "<gray>Auto-open disabled.</gray>"));
        open(player, pack);
    }

    private ActionButton cancelButton(Player player) {
        return ActionButton.builder(Text.parse("Close")).width(BUTTON_WIDTH)
                .action(DialogAction.customClick((view, audience) -> returnToStorage(player), ClickCallback.Options.builder().build()))
                .build();
    }
}
