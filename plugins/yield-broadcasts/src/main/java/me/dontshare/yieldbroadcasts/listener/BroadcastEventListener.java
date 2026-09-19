package me.dontshare.yieldbroadcasts.listener;

import me.dontshare.yieldbroadcasts.data.BroadcastsContentLoader.BroadcastsContent;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.ItemDefinition;
import me.dontshare.yieldpacks.data.Rarity;
import me.dontshare.yieldpacks.event.PackOpenedEvent;
import me.dontshare.yieldpacks.event.PetFusedEvent;
import me.dontshare.yieldpacks.event.ShardFoundEvent;
import me.dontshare.yieldpacks.fusion.FusionTier;
import me.dontshare.yieldpacks.roll.PackRollService.RollResult;
import me.dontshare.yieldrebirth.event.RebirthEvent;
import me.dontshare.yieldskilltree.event.PrestigeEvent;
import me.dontshare.yieldteams.event.TeamCreatedEvent;
import me.dontshare.yieldzones.event.WorldBossKilledEvent;
import me.dontshare.yieldzones.event.ZoneUnlockedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Reacts to every "big moment" cross-plugin event and, if that trigger's own broadcasts.yml block is enabled, sends a server-wide chat message. */
public final class BroadcastEventListener implements Listener {

    private final Supplier<BroadcastsContent> content;
    private final YieldPacks packs;

    public BroadcastEventListener(Supplier<BroadcastsContent> content, YieldPacks packs) {
        this.content = content;
        this.packs = packs;
    }

    @EventHandler
    public void onPackOpened(PackOpenedEvent event) {
        var config = content.get().packOpen();
        if (!config.enabled()) {
            return;
        }
        for (RollResult roll : event.getRolls()) {
            ItemDefinition item = roll.item();
            // A Huge and a very lucky pull each get their own, better
            // message below - announcing the same pull twice would read as
            // a bug, and the rarity-tier line is the least interesting of
            // the three things we could say about it.
            if (announceHuge(event, roll) | announceLuckyPull(event, roll)) {
                continue;
            }
            if (!config.rarityIds().contains(item.rarityId())) {
                continue;
            }
            Rarity rarity = packs.getRarityRegistry().find(item.rarityId()).orElse(null);
            Component rarityName = rarity != null
                    ? Text.parse("<" + rarity.colorHex() + "><bold>" + rarity.displayName() + "</bold></" + rarity.colorHex() + ">")
                    : Component.text(item.rarityId());
            Component petName = petDisplayComponent(item);
            broadcast(config.messageTemplate(),
                    Placeholder.unparsed("player", event.getPlayer().getName()),
                    Placeholder.component("rarity_name", rarityName),
                    Placeholder.component("pet_name", petName));
        }
    }

    /** True if this pull was announced as a Huge, so the caller knows not to announce it again. */
    private boolean announceHuge(PackOpenedEvent event, RollResult roll) {
        var config = content.get().huge();
        if (!config.enabled() || !roll.huge()) {
            return false;
        }
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("player", event.getPlayer().getName()),
                Placeholder.component("pet_name", petDisplayComponent(roll.item())),
                Placeholder.unparsed("one_in", Formatting.format(roll.oneIn())));
        return true;
    }

    /** True if this pull cleared the "worth telling everyone" odds threshold and was announced. */
    private boolean announceLuckyPull(PackOpenedEvent event, RollResult roll) {
        var config = content.get().luckyPull();
        if (!config.enabled() || roll.oneIn() < config.minOneIn()) {
            return false;
        }
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("player", event.getPlayer().getName()),
                Placeholder.component("pet_name", petDisplayComponent(roll.item())),
                Placeholder.unparsed("one_in", Formatting.format(roll.oneIn())));
        return true;
    }

    @EventHandler
    public void onPetFused(PetFusedEvent event) {
        var config = content.get().fusion();
        if (!config.enabled()) {
            return;
        }
        ItemDefinition item = packs.getItemRegistry().find(event.getResultId()).orElse(null);
        if (item == null || item.fusionTier() == null || !config.tierNames().contains(item.fusionTier().name())) {
            return;
        }
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("player", event.getPlayer().getName()),
                Placeholder.component("pet_name", petDisplayComponent(item)));
    }

    @EventHandler
    public void onRebirth(RebirthEvent event) {
        var config = content.get().rebirth();
        if (!config.enabled() || event.getTotalRebirths() % config.every() != 0) {
            return;
        }
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("player", event.getPlayer().getName()),
                Placeholder.unparsed("total", Formatting.format((double) event.getTotalRebirths())));
    }

    @EventHandler
    public void onPrestige(PrestigeEvent event) {
        var config = content.get().prestige();
        if (!config.enabled()) {
            return;
        }
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("player", event.getPlayer().getName()),
                Placeholder.unparsed("total", String.valueOf(event.getTotalPrestiges())));
    }

    @EventHandler
    public void onZoneUnlocked(ZoneUnlockedEvent event) {
        var config = content.get().zoneUnlock();
        if (!config.enabled()) {
            return;
        }
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("player", event.getPlayer().getName()),
                Placeholder.unparsed("zone", Formatting.stripLeadingColorCodes(event.getZone().displayName())));
    }

    @EventHandler
    public void onTeamCreated(TeamCreatedEvent event) {
        var config = content.get().teamCreate();
        if (!config.enabled()) {
            return;
        }
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("player", event.getPlayer().getName()),
                Placeholder.unparsed("team", event.getTeam().getName()));
    }

    @EventHandler
    public void onShardFound(ShardFoundEvent event) {
        // Common finds happen constantly once a player has an unlock -
        // only the rare Perfect variant is worth a server-wide message.
        if (!event.isPerfect()) {
            return;
        }
        var config = content.get().shardFind();
        if (!config.enabled()) {
            return;
        }
        String label = event.getType().name().charAt(0) + event.getType().name().substring(1).toLowerCase();
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("player", event.getPlayer().getName()),
                Placeholder.unparsed("shard_type", label));
    }

    @EventHandler
    public void onWorldBossKilled(WorldBossKilledEvent event) {
        var config = content.get().worldBoss();
        if (!config.enabled()) {
            return;
        }
        Map<UUID, Long> damageByPlayer = event.getDamageByPlayer();
        UUID topId = null;
        long topDamage = -1;
        for (Map.Entry<UUID, Long> entry : damageByPlayer.entrySet()) {
            if (entry.getValue() > topDamage) {
                topDamage = entry.getValue();
                topId = entry.getKey();
            }
        }
        Player top = topId != null ? Bukkit.getPlayer(topId) : null;
        String topName = top != null ? top.getName() : "nobody";
        broadcast(config.messageTemplate(),
                Placeholder.unparsed("boss", Formatting.stripLeadingColorCodes(event.getDefinition().displayName())),
                Placeholder.unparsed("top_player", topName),
                Placeholder.unparsed("top_damage", Formatting.format((double) Math.max(0, topDamage))),
                Placeholder.unparsed("contributors", String.valueOf(damageByPlayer.size())));
    }

    private Component petDisplayComponent(ItemDefinition item) {
        FusionTier tier = item.fusionTier();
        String tag = tier != null ? tier.tag() : null;
        String plain = Formatting.stripLeadingColorCodes(item.displayName());
        return tag != null
                ? Text.parse(tag + " <name>", Placeholder.unparsed("name", plain))
                : Component.text(plain);
    }

    private void broadcast(String template, TagResolver... placeholders) {
        Bukkit.broadcast(Text.parse(template, placeholders));
    }
}
