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
import me.dontshare.yieldpacks.gui.PackStorageGui;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackOpenService;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Replaces the old bulk QuantityPickerDialog - a stored pack can only be
 * opened one at a time now, so this just offers "Open 1" plus a toggle for
 * auto-opening this pack (which repeats "Open 1" on a cooldown, from the
 * player's storage screen, until it runs out - see PackOpenService).
 */
public final class OpenPackDialog {

    private static final int BUTTON_WIDTH = 150;

    private final PlayerDataStore<PackPlayerProfile> playerStore;
    private final PackOpenService openService;
    private PackStorageGui packStorageGui;

    public OpenPackDialog(PlayerDataStore<PackPlayerProfile> playerStore, PackOpenService openService) {
        this.playerStore = playerStore;
        this.openService = openService;
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

        List<ActionButton> buttons = List.of(
                ActionButton.builder(Text.parse("Open 1"))
                        .width(BUTTON_WIDTH)
                        .action(DialogAction.customClick((view, audience) -> {
                                    openService.tryOpen(player, pack.id(), PackOpenService.OpenTrigger.DIALOG);
                                    // The dialog itself is a static snapshot from when it was
                                    // built - Paper doesn't live-update a shown Dialog's body/
                                    // buttons - so refresh it to reflect the new stored count,
                                    // unless that was the last one, in which case there's
                                    // nothing left to open and the screen should just close.
                                    PackPlayerProfile updated = playerStore.getCached(player.getUniqueId());
                                    int remaining = updated != null ? updated.getStoredPacks().getOrDefault(pack.id(), 0) : 0;
                                    if (remaining > 0) {
                                        open(player, pack);
                                    } else {
                                        returnToStorage(player);
                                    }
                                },
                                ClickCallback.Options.builder().build()))
                        .build(),
                ActionButton.builder(Text.parse(autoOpenOn ? "Disable Auto-Open" : "Enable Auto-Open"))
                        .width(BUTTON_WIDTH)
                        .action(DialogAction.customClick((view, audience) -> toggleAutoOpen(player, pack),
                                ClickCallback.Options.builder().build()))
                        .build());

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Text.parse(pack.displayName()))
                        .body(List.of(DialogBody.plainMessage(Text.parse("&7Stored: &f" + stored))))
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
