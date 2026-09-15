package me.dontshare.yieldtrade.gui;

import me.dontshare.yieldcore.item.ItemBuilder;
import me.dontshare.yieldtrade.session.TradeSession;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * One player's view of a {@link TradeSession} - their own offer on the left,
 * their partner's mirrored on the right.
 * <p>
 * Deliberately NOT built on yield-core's {@code Gui}: that framework's
 * editable-slot mode hands the click back to vanilla to resolve, which is
 * right for a single-player input grid and wrong here. Every slot of this
 * screen is a redrawn copy of {@link TradeSession} state and every click
 * against it is cancelled outright (see {@link TradeGuiListener}), so the
 * items a player is looking at are pictures of the offer, never the offer
 * itself. Being its own {@link InventoryHolder} also keeps yield-core's
 * {@code GuiListener} from ever seeing this inventory at all.
 */
public final class TradeGui implements InventoryHolder {

    public static final int[] OWN_SLOTS = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21};
    public static final int[] PARTNER_SLOTS = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26};
    /** The column between the two halves, minus the rows that carry buttons. */
    private static final int[] DIVIDER_SLOTS = {4, 13, 22, 31};

    public static final int ACCEPT_SLOT = 38;
    public static final int INFO_SLOT = 40;
    public static final int PARTNER_STATUS_SLOT = 42;
    public static final int CANCEL_SLOT = 49;

    private static final int SIZE = 54;

    private final TradeSession session;
    private final UUID viewerId;
    private final Inventory inventory;

    public TradeGui(TradeSession session, UUID viewerId, String partnerName) {
        this.session = session;
        this.viewerId = viewerId;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text("Trading with " + partnerName));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public TradeSession getSession() {
        return session;
    }

    public UUID getViewerId() {
        return viewerId;
    }

    /** The offer index this screen slot maps to, or -1 if it isn't one of the viewer's own item slots. */
    public static int ownOfferIndex(int slot) {
        for (int i = 0; i < OWN_SLOTS.length; i++) {
            if (OWN_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** Repaints every slot from the session. Cheap enough to just call on any change. */
    public void render() {
        List<ItemStack> own = session.offerOf(viewerId);
        List<ItemStack> theirs = session.offerOf(session.partnerOf(viewerId));

        for (int i = 0; i < OWN_SLOTS.length; i++) {
            inventory.setItem(OWN_SLOTS[i], i < own.size() ? own.get(i).clone() : emptySlotMarker());
        }
        for (int i = 0; i < PARTNER_SLOTS.length; i++) {
            inventory.setItem(PARTNER_SLOTS[i], i < theirs.size() ? theirs.get(i).clone() : emptySlotMarker());
        }

        ItemStack divider = ItemBuilder.of(Material.BLACK_STAINED_GLASS_PANE).name(" ").hideTooltip().build();
        for (int slot : DIVIDER_SLOTS) {
            inventory.setItem(slot, divider);
        }
        for (int slot = 27; slot < SIZE; slot++) {
            if (slot == ACCEPT_SLOT || slot == INFO_SLOT || slot == PARTNER_STATUS_SLOT || slot == CANCEL_SLOT) {
                continue;
            }
            inventory.setItem(slot, divider);
        }

        inventory.setItem(ACCEPT_SLOT, acceptButton());
        inventory.setItem(PARTNER_STATUS_SLOT, partnerStatus());
        inventory.setItem(INFO_SLOT, infoIcon());
        inventory.setItem(CANCEL_SLOT, ItemBuilder.of(Material.BARRIER)
                .name("<red><bold>Cancel Trade</bold>")
                .lore(List.of(" ", "<gray>Closes the trade and", "<gray>returns everything offered."))
                .hideAttributes()
                .build());
    }

    private ItemStack emptySlotMarker() {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name(" ").hideTooltip().build();
    }

    private ItemStack acceptButton() {
        boolean accepted = session.hasAccepted(viewerId);
        return ItemBuilder.of(accepted ? Material.LIME_DYE : Material.GRAY_DYE)
                .name(accepted ? "<green><bold>Accepted</bold>" : "<yellow><bold>Click to Accept</bold>")
                .lore(accepted
                        ? List.of(" ", "<gray>Waiting on the other player.", " ", "<yellow>Click <gray>to take it back.")
                        : List.of(" ", "<gray>Accept this trade.", "<gray>Changing either offer", "<gray>resets both accepts."))
                .hideAttributes()
                .build();
    }

    private ItemStack partnerStatus() {
        boolean accepted = session.hasAccepted(session.partnerOf(viewerId));
        return ItemBuilder.of(accepted ? Material.LIME_DYE : Material.RED_DYE)
                .name(accepted ? "<green><bold>They Accepted</bold>" : "<red><bold>Not Accepted</bold>")
                .lore(List.of(" ", accepted
                        ? "<gray>They're ready to trade."
                        : "<gray>They haven't accepted yet."))
                .hideAttributes()
                .build();
    }

    private ItemStack infoIcon() {
        if (session.getState() == TradeSession.State.CONFIRMING) {
            int seconds = Math.max(0, (session.getConfirmTicksRemaining() + 19) / 20);
            return ItemBuilder.of(Material.CLOCK)
                    .name("<green><bold>Trading in " + seconds + "s</bold>")
                    .lore(List.of(" ", "<gray>Both players accepted.", "<gray>Touching anything cancels it."))
                    .hideAttributes()
                    .build();
        }
        return ItemBuilder.of(Material.BOOK)
                .name("<#4BD9FF><bold>How to Trade</bold>")
                .lore(List.of(
                        " ",
                        "<gray>Click an item in your inventory",
                        "<gray>to put it up. <yellow>Right-click <gray>offers one.",
                        " ",
                        "<gray>Click it again up here to take it back.",
                        " ",
                        "<gray>Both sides must accept."))
                .hideAttributes()
                .build();
    }

}
