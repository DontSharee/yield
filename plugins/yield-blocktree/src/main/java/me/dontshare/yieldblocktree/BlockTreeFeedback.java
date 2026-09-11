package me.dontshare.yieldblocktree;

import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.event.ShardFoundEvent;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.shard.ShardType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The shared "a block just got broken toward its tree" reaction - tier-cross
 * announcement + shard-drop roll - factored out of {@code
 * BlockTreeProgressListener} so BOTH progress sources (a pet kill via
 * {@code OreCubeKilledEvent}, and a player directly mining a real ore block
 * via yield-mining) react identically through one shared entry point rather
 * than each plugin re-implementing its own copy of the same feedback.
 */
public final class BlockTreeFeedback {

    private static final Duration FADE_IN = Duration.ofMillis(200);
    private static final Duration STAY = Duration.ofSeconds(2);
    private static final Duration FADE_OUT = Duration.ofMillis(500);
    /** Of whatever roll actually hits a shard drop, this fraction becomes "Perfect" instead of common - a second, independent roll on TOP of the base chance, not a separate pool competing with it. */
    private static final double PERFECT_SHARE = 0.05;

    private final BlockTreeService service;
    private final YieldPacks packs;

    public BlockTreeFeedback(BlockTreeService service, YieldPacks packs) {
        this.service = service;
        this.packs = packs;
    }

    /** Records one break of {@code material} for {@code player} - advances blocktree progress, announces any newly-crossed tier, and rolls for a shard drop. The single call every progress source should make. */
    public void recordBreakAndAnnounce(Player player, Material material) {
        List<BlockTreeService.TierCrossed> crossed = service.recordBreak(player, material);
        for (BlockTreeService.TierCrossed tc : crossed) {
            announceTier(player, material, tc);
        }
        rollShardDrops(player, material);
    }

    private void announceTier(Player player, Material material, BlockTreeService.TierCrossed tc) {
        String blockName = Formatting.stripLeadingColorCodes(service.displayNameOf(material));
        Component main = Text.parse("<#4BD9FF><bold>Blocktree Tier " + (tc.tierIndex() + 1) + "!</bold></#4BD9FF>");
        Component sub = Text.parse("<gray><name> - open /blocktree to claim!</gray>", Placeholder.unparsed("name", blockName));
        player.showTitle(Title.title(main, sub, Title.Times.times(FADE_IN, STAY, FADE_OUT)));
        // A rising power-up swell rather than a generic UI toast blip - a
        // tier crossing is a real permanent-stat moment, it should sound
        // like one.
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.3f);
    }

    private void rollShardDrops(Player player, Material material) {
        PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
        if (profile == null) {
            return;
        }
        for (ShardType type : ShardType.values()) {
            double chance = service.shardDropChance(profile, material, type.name());
            if (chance <= 0 || ThreadLocalRandom.current().nextDouble() >= chance) {
                continue;
            }
            boolean perfect = ThreadLocalRandom.current().nextDouble() < PERFECT_SHARE;
            giveShard(player, type, perfect);
        }
    }

    private void giveShard(Player player, ShardType type, boolean perfect) {
        ItemStack item = packs.getShardItem().create(type, perfect);
        player.getInventory().addItem(item).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        String label = type.name().charAt(0) + type.name().substring(1).toLowerCase(Locale.ROOT);
        if (perfect) {
            // Distinct from both Rebirth's dragon growl and a shard's own
            // consume "poof" - a conduit awakening reads as "something
            // ancient and powerful just activated," fitting for the
            // rarest possible find, layered with a bright chime on top.
            player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 1f, 1.1f);
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.6f);
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 40, 0.5, 0.5, 0.5, 0.15);
            player.sendMessage(Text.parse("<gradient:#FFD700:#FF66CC><bold>PERFECT <type> SHARD!</bold></gradient>",
                    Placeholder.unparsed("type", label.toUpperCase(Locale.ROOT))));
        } else {
            // A short crystal "ding" - distinct from a cube-kill's own
            // orb-pickup sound, so a shard find doesn't blend into it.
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.8f, 1.5f);
            player.sendMessage(Text.parse("<#B15CFF>Found a <type> Shard!</#B15CFF>", Placeholder.unparsed("type", label)));
        }
        Bukkit.getPluginManager().callEvent(new ShardFoundEvent(player, type, perfect));
    }
}
