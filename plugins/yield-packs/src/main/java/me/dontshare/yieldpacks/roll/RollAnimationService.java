package me.dontshare.yieldpacks.roll;

import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.data.RarityRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * The "exciting" roll reveal - a player Title (with a sound) shown after
 * opening packs, highlighting the best pet obtained. Skipped entirely when
 * the player has roll animation disabled (see /rollanimation) -
 * QuantityPickerDialog still sends the plain chat summary either way.
 */
public final class RollAnimationService {

    private static final Duration FADE_IN = Duration.ofMillis(200);
    private static final Duration STAY = Duration.ofSeconds(2);
    private static final Duration FADE_OUT = Duration.ofMillis(500);

    private final Supplier<RarityRegistry> rarityRegistry;

    public RollAnimationService(Supplier<RarityRegistry> rarityRegistry) {
        this.rarityRegistry = rarityRegistry;
    }

    public void play(Player player, List<PackRollService.RollResult> rolls) {
        PackRollService.RollResult best = rolls.stream()
                .max(Comparator.comparingDouble(r -> r.item().valuePerSecond()))
                .orElse(null);
        if (best == null) {
            return;
        }
        ItemDefinition item = best.item();
        Rarity rarity = rarityRegistry.get().find(item.rarityId()).orElse(null);
        String rarityColor = rarity != null ? rarity.colorHex() : "#FFFFFF";

        Component mainTitle = rolls.size() == 1
                ? Text.parse("<" + rarityColor + "><bold>" + item.displayName() + "</bold>")
                : Text.parse("<#4BD9FF><bold>" + rolls.size() + "x Opened!</bold>");
        Component subtitle = rolls.size() == 1
                ? (rarity != null ? Text.parse("<gray>" + rarity.displayName() + "</gray>") : Component.empty())
                : Text.parse("<gray>Best: </gray><" + rarityColor + ">" + item.displayName());

        player.showTitle(Title.title(mainTitle, subtitle, Title.Times.times(FADE_IN, STAY, FADE_OUT)));

        float pitch = rarity != null ? (float) Math.min(2.0, 1.0 + rarity.sortOrder() * 0.15) : 1f;
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, pitch);
    }
}
