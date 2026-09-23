package me.dontshare.yieldcore.trash;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.dontshare.yieldcore.text.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * /trash - a plain, fully editable chest with nothing in it. Whatever is
 * left inside when it closes is gone for good.
 * <p>
 * Deliberately not a {@code Gui}: GuiListener locks every slot of a Gui by
 * default, and this screen has no buttons to protect - it is vanilla item
 * movement from top to bottom. It gets its own holder type instead, so the
 * close handler can recognise it without touching any other inventory.
 */
public final class TrashCommand implements Listener {

    private static final int ROWS = 4;

    /** Marker holder - the inventory it owns is created fresh per open and never stored. */
    private static final class TrashHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("trash")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Text.parse("<gray>Players only.</gray>"));
                        return Command.SINGLE_SUCCESS;
                    }
                    open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
    }

    public void open(Player player) {
        TrashHolder holder = new TrashHolder();
        holder.inventory = Bukkit.createInventory(holder, ROWS * 9, Text.parse("<dark_gray>Trash</dark_gray>"));
        player.openInventory(holder.inventory);
    }

    /** Runs on every close path - Esc, another menu opening, quit, kick, death. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof TrashHolder)) {
            return;
        }
        int destroyed = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) {
                destroyed += item.getAmount();
            }
        }
        inventory.clear();
        if (destroyed > 0 && event.getPlayer() instanceof Player player) {
            player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.4f, 1.2f);
            player.sendMessage(Text.parse("<gray>Trashed <white><count></white> item<s>.</gray>",
                    Placeholder.unparsed("count", String.valueOf(destroyed)),
                    Placeholder.unparsed("s", destroyed == 1 ? "" : "s")));
        }
    }

    /** A reload or shutdown closes every open trash first, so nothing survives in a half-open chest. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof TrashHolder) {
                player.closeInventory();
            }
        }
    }
}
