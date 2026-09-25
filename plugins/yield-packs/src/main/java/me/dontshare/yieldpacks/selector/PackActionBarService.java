package me.dontshare.yieldpacks.selector;

import me.dontshare.yieldcore.database.PlayerDataStore;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.PackContentLoader;
import me.dontshare.yieldpacks.pity.PityService;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.roll.PackOpenService;
import me.dontshare.yieldpacks.roll.RevealSuppressionRegistry;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Supplier;

/**
 * Keeps the egg in front of you and your pity progress always visible on
 * the action bar. Mirrors YieldScoreboardDisplay's shape.
 * <p>
 * It used to read "Selected: X", from when a pack was a thing you picked
 * and then opened anywhere. There is no selection any more - you hatch what
 * you are standing at - so it reports THAT when you are at a station
 * (along with whether auto-hatch is running and at what size, which is
 * otherwise invisible once the menu is closed) and falls back to the last
 * egg you hatched when you are not.
 */
public final class PackActionBarService {

    private final JavaPlugin plugin;
    private final PlayerDataStore<PackPlayerProfile> store;
    private final Supplier<PackContentLoader.ContentSnapshot> content;
    private final PityService pityService;
    private final PackSelectorService selectorService;
    private final PackOpenService openService;

    public PackActionBarService(JavaPlugin plugin, PlayerDataStore<PackPlayerProfile> store,
                                 Supplier<PackContentLoader.ContentSnapshot> content, PityService pityService,
                                 PackSelectorService selectorService, PackOpenService openService) {
        this.plugin = plugin;
        this.store = store;
        this.content = content;
        this.pityService = pityService;
        this.selectorService = selectorService;
        this.openService = openService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, me.dontshare.yieldcore.perf.PerfTracker.timed("pets.actionbar", this::tick), 0L, 20L);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (RevealSuppressionRegistry.isActive(player.getUniqueId())) {
                // A pack-reveal reel (see PackRevealAnimationService) owns
                // this player's action bar for its own duration - writing
                // here too would flicker the two against each other every
                // tick.
                continue;
            }
            PackPlayerProfile profile = store.getCached(player.getUniqueId());
            if (profile == null) {
                continue;
            }
            // Piggybacks on this once-a-second loop to also keep the Pack
            // Selector item's lore in sync with the current selection,
            // wherever it was last changed (the compass itself, or a GUI).
            selectorService.refreshItem(player);
            String siteId = openService.hatchSiteFor(player);
            String label = siteId != null ? "At" : "Last";
            String auto = siteId != null && profile.isAutoOpenEnabled()
                    ? " <gray>|</gray> <#55FF7F>Auto " + profile.getAutoHatchAmount() + "x</#55FF7F>"
                    : "";
            player.sendActionBar(Text.parse(
                    "<#4BD9FF><label>: <white><pack></white></#4BD9FF><auto> <gray>|</gray> <#4BD9FF>Pity: <bar> <gray><suffix></gray>",
                    Placeholder.unparsed("label", label),
                    Placeholder.unparsed("pack", packName(siteId != null ? siteId : profile.getActivePackId())),
                    Placeholder.parsed("auto", auto),
                    Placeholder.parsed("bar", pityService.renderBar(profile.getRollCount())),
                    Placeholder.unparsed("suffix", pityService.renderProgressSuffix(profile.getRollCount()))));
        }
    }

    private String packName(String packId) {
        if (packId == null) {
            return "None";
        }
        return content.get().packs().find(packId)
                .map(pack -> Formatting.stripLeadingColorCodes(pack.displayName()))
                .orElse("None");
    }
}
