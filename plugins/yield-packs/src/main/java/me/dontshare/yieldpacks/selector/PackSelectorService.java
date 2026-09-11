package me.dontshare.yieldpacks.selector;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.gui.PackStorageGui;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackOpenService;
import me.dontshare.yieldpacks.roll.RevealSuppressionRegistry;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * The logic behind the physical Pack Selector item - kept separate from
 * PackSelectorListener so the Bukkit event plumbing doesn't get tangled up
 * with the actual cycle/open/give behavior.
 */
public final class PackSelectorService {

    private final PackSelectorItem item;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PackOpenService openService;
    private final PackStorageGui packStorageGui;

    public PackSelectorService(PackSelectorItem item, PlayerDataStore<PackPlayerProfile> store,
                                Supplier<PackContentLoader.ContentSnapshot> content, PackOpenService openService,
                                PackStorageGui packStorageGui) {
        this.item = item;
        this.store = store;
        this.content = content;
        this.openService = openService;
        this.packStorageGui = packStorageGui;
    }

    /** Makes sure slot 4 holds the selector - self-healing, so a lost/misplaced item just reappears next join. */
    public void ensureItem(Player player) {
        if (!item.isPackSelector(player.getInventory().getItem(4))) {
            PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
            player.getInventory().setItem(4, item.create(selectedPackName(profile)));
        }
    }

    /**
     * Rewrites slot 4's item so its lore matches the current selection - a
     * no-op if slot 4 doesn't currently hold the selector, so this never
     * fights a Creative player who's deliberately swapped it out (unlike
     * {@link #ensureItem}, which is only ever called at join).
     */
    public void refreshItem(Player player) {
        if (!item.isPackSelector(player.getInventory().getItem(4))) {
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        player.getInventory().setItem(4, item.create(selectedPackName(profile)));
    }

    /**
     * Left-click behavior: opens the SAME storage screen {@code /packs}
     * does - previously a separate, simpler pack-picker GUI, merged into
     * one so the compass and the command are the exact same feature rather
     * than two overlapping ones.
     */
    public void openSelectMenu(Player player) {
        packStorageGui.open(player);
    }

    /**
     * Right-click behavior: opens the selected pack, unless auto-open is
     * already handling it. Uses Minecraft's own item-cooldown overlay
     * (rather than a chat message) to communicate the open cooldown - while
     * the compass is visibly on cooldown, this does nothing at all.
     */
    public void attemptOpen(Player player) {
        if (player.hasCooldown(Material.COMPASS)) {
            return;
        }
        if (RevealSuppressionRegistry.isActive(player.getUniqueId())) {
            // A reel (see PackRevealAnimationService) is still mid-flight for
            // this player - the compass's own item-cooldown is only ~1s
            // (open-cooldown-seconds) while a reel can run several seconds
            // longer, so without this a second open could fire while the
            // first reel's entities are still on screen, spawning two full
            // sets of slots on top of each other.
            return;
        }
        PackPlayerProfile profile = store.getOrCreate(player.getUniqueId());
        String packId = profile.getActivePackId();
        if (packId == null) {
            player.sendMessage(Text.parse("<red>You don't have a pack selected - left-click to pick one.</red>"));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        if (profile.isAutoOpenEnabled()) {
            player.sendMessage(Text.parse("<gray>Auto-open is already handling <white><pack></white>.</gray>",
                    Placeholder.unparsed("pack", packName(packId))));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        if (openService.tryOpen(player, packId, PackOpenService.OpenTrigger.MANUAL_COMPASS)) {
            player.setCooldown(Material.COMPASS, cooldownTicks());
        }
    }

    private int cooldownTicks() {
        return (int) Math.max(1L, content.get().shop().openCooldownMillis() / 50L);
    }

    private String selectedPackName(PackPlayerProfile profile) {
        String activeId = profile.getActivePackId();
        return activeId == null ? "None" : packName(activeId);
    }

    private String packName(String packId) {
        String raw = content.get().packs().find(packId).map(PackDefinition::displayName).orElse(packId);
        return Formatting.stripLeadingColorCodes(raw);
    }
}
