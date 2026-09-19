package me.dontshare.yieldpackstations.display;

import com.github.retrooper.packetevents.util.Vector3f;
import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.EntityClickRegistry;
import me.dontshare.yieldcore.packet.InteractionEntityManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.data.PackDefinition;
import me.dontshare.yieldpackstations.PackStationService;
import me.dontshare.yieldpackstations.data.PackStation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The packet-visual layer for physical pack stations - near-identical to
 * yield-upgrades' own {@code UpgradeStationDisplay} (invisible clickable
 * hitbox + visible button + floating text readout, spawned per-viewer
 * purely by distance), with one deliberate structural difference: this
 * registers via {@link EntityClickRegistry#register} (left-click/attack -
 * the "smack") rather than {@code registerInteract} (right-click), per the
 * user's explicit request that buying a pack is something you smack, not
 * right-click.
 */
public final class PackStationDisplay {

    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;
    private static final long TICK_INTERVAL = 20L; // 1 second
    private static final float BUTTON_SCALE = 0.35f;
    private static final float BUTTON_TRANSLATE = (1f - BUTTON_SCALE) / 2f;
    private static final float HITBOX_SIZE = 0.8f;
    // Decoupled from HITBOX_SIZE's own (1-size)/2 corner-inset formula -
    // UpgradeStationDisplay#hitboxLocation's own javadoc documents this same
    // shared Interaction-anchor quirk empirically: inset 0.5 (centered)
    // overshot, 0.3 overshot WORSE, 0.1 was the best measured point, and
    // its own next-step note said to try less than 0.1 next. Three rounds
    // of in-game screenshot feedback on a pack station since: (1) visibly
    // above/left of the button - took X/Y/Z to 0; (2) closer but still off,
    // nudge toward +Z - added a separate Z-only offset; (3) still off, more
    // +Z and +0.1 Y - X stays at the 0 data point, Y/Z each get their own.
    private static final float HITBOX_INSET_X = 0.0f;
    private static final float HITBOX_INSET_Y = 0.1f;
    private static final float HITBOX_INSET_Z = 0.4f;
    private static final float PUSH_SCALE = BUTTON_SCALE * 0.6f;
    private static final float PUSH_TRANSLATE = (1f - PUSH_SCALE) / 2f;
    private static final int PUSH_TICKS = 3;
    private static final float WALL_FACE_SCALE = 0.9f;
    private static final float WALL_DEPTH_SCALE = 0.15f;
    private static final float WALL_FACE_TRANSLATE = (1f - WALL_FACE_SCALE) / 2f;
    private static final float WALL_DEPTH_TRANSLATE = (1f - WALL_DEPTH_SCALE) / 2f;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final PackStationService stationService;

    private volatile List<PackStation> stations = List.of();
    private final Map<PackStation, Set<UUID>> viewersByStation = new ConcurrentHashMap<>();
    /** What each viewer was last actually shown per station, so an unchanged sign costs nothing - see {@link #refreshFor}. */
    private final Map<UUID, Map<PackStation, String>> lastRenderState = new ConcurrentHashMap<>();

    public PackStationDisplay(JavaPlugin plugin, YieldPacks packs, PackStationService stationService) {
        this.plugin = plugin;
        this.packs = packs;
        this.stationService = stationService;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /** Same teardown-then-rebuild reasoning as UpgradeStationDisplay#reload - a content reload replaces every PackStation (and its entity ids) wholesale. */
    public void reload(List<PackStation> newStations) {
        for (PackStation station : stations) {
            EntityClickRegistry.unregister(station.hitboxEntityId());
            Set<UUID> viewers = viewersByStation.remove(station);
            if (viewers == null) {
                continue;
            }
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    despawnFor(viewer, station);
                }
            }
        }
        stations = newStations;
        for (PackStation station : newStations) {
            viewersByStation.put(station, ConcurrentHashMap.newKeySet());
            EntityClickRegistry.register(station.hitboxEntityId(), player -> handleClick(player, station));
        }
    }

    private void tick() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (PackStation station : stations) {
                Set<UUID> viewers = viewersByStation.get(station);
                if (viewers == null) {
                    continue;
                }
                boolean inRange = viewer.getWorld().equals(station.location().getWorld())
                        && station.location().distanceSquared(viewer.getLocation()) <= VIEW_DISTANCE_SQUARED;
                boolean seeing = viewers.contains(viewer.getUniqueId());
                if (inRange && !seeing) {
                    spawnFor(viewer, station);
                    viewers.add(viewer.getUniqueId());
                } else if (!inRange && seeing) {
                    despawnFor(viewer, station);
                    viewers.remove(viewer.getUniqueId());
                } else if (inRange) {
                    // Re-render every tick, not just after a purchase - a
                    // black-market station's live pack/cost can change
                    // mid-view the instant its rotation flips, and a coin
                    // balance can change from something unrelated too.
                    refreshFor(viewer, station);
                }
            }
        }
    }

    private void handleClick(Player player, PackStation station) {
        playPushAnimation(player, station);
        // Sneak to buy a stack at once - see PackStationService#BULK_PURCHASE_AMOUNT.
        PackStationService.Purchase purchase = stationService.attemptPurchase(player, station, player.isSneaking());
        switch (purchase.result()) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                PackDefinition pack = packs.getPackRegistry().find(stationService.currentPackId(station)).orElse(null);
                player.sendMessage(Text.parse("<green>Bought <count>x <name>!</green>",
                        Placeholder.unparsed("count", String.valueOf(purchase.quantity())),
                        Placeholder.unparsed("name", pack != null ? Formatting.stripLeadingColorCodes(pack.displayName()) : "pack")));
                refreshFor(player, station);
            }
            case ZONE_LOCKED -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You haven't unlocked this zone.</red>"));
            }
            case CANT_AFFORD -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You can't afford this pack yet.</red>"));
            }
            case NO_STOCK_CONFIGURED -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>This station has nothing to sell right now.</red>"));
            }
        }
    }

    private void refreshFor(Player viewer, PackStation station) {
        Set<UUID> viewers = viewersByStation.get(station);
        if (viewers == null || !viewers.contains(viewer.getUniqueId())) {
            return;
        }
        // Everything shown here is derived from which pack the station is
        // currently selling and whether this viewer can afford it, and this
        // runs for every in-range viewer of every station once a second.
        // Rebuilding regardless meant a MiniMessage parse and two packets per
        // viewer per station to redraw exactly what was already on screen.
        boolean canAfford = stationService.canAfford(viewer, station);
        String state = stationService.currentPackId(station) + "|" + canAfford;
        Map<PackStation, String> perStation =
                lastRenderState.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>());
        if (state.equals(perStation.get(station))) {
            return;
        }
        perStation.put(station, state);

        TextDisplayManager.setText(viewer, station.textEntityId(), buildText(viewer, station));
        BlockDisplayManager.setBlockState(viewer, station.buttonEntityId(),
                canAfford ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
    }

    private void playPushAnimation(Player viewer, PackStation station) {
        BlockDisplayManager.setInterpolation(viewer, station.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
        BlockDisplayManager.setTransformation(viewer, station.buttonEntityId(), PUSH_TRANSLATE, PUSH_SCALE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            BlockDisplayManager.setInterpolation(viewer, station.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
            BlockDisplayManager.setTransformation(viewer, station.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);
        }, PUSH_TICKS);
    }

    private void spawnFor(Player viewer, PackStation station) {
        Location loc = station.location();

        PacketEntityManager.beginBundle(viewer);
        InteractionEntityManager.spawn(viewer, station.hitboxEntityId(), hitboxLocation(loc));
        InteractionEntityManager.setSize(viewer, station.hitboxEntityId(), HITBOX_SIZE, HITBOX_SIZE);

        BlockDisplayManager.spawn(viewer, station.wallEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, station.wallEntityId(),
                station.isBlackMarket() ? Material.BLACK_CONCRETE : Material.GRAY_CONCRETE);
        BlockDisplayManager.setTransformation(viewer, station.wallEntityId(),
                new Vector3f(WALL_FACE_TRANSLATE, WALL_FACE_TRANSLATE, WALL_DEPTH_TRANSLATE),
                new Vector3f(WALL_FACE_SCALE, WALL_FACE_SCALE, WALL_DEPTH_SCALE));

        BlockDisplayManager.spawn(viewer, station.buttonEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, station.buttonEntityId(),
                stationService.canAfford(viewer, station) ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        BlockDisplayManager.setTransformation(viewer, station.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);

        TextDisplayManager.spawn(viewer, station.textEntityId(), textLocation(loc));
        TextDisplayManager.setBillboard(viewer, station.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, station.textEntityId(), 0x00000000);
        TextDisplayManager.setStyle(viewer, station.textEntityId(), true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setText(viewer, station.textEntityId(), buildText(viewer, station));
        PacketEntityManager.endBundle(viewer);
    }

    /** See HITBOX_INSET's own comment - same corner-anchored Interaction quirk as UpgradeStationDisplay#hitboxLocation, tuned independently. */
    private Location hitboxLocation(Location stationCorner) {
        return stationCorner.clone().add(HITBOX_INSET_X, HITBOX_INSET_Y, HITBOX_INSET_Z);
    }

    /** X pulled back from the cell-center 0.5 - in-game feedback was that the text floated above the raw hitbox corner rather than above the button itself, so it needed pushing toward -X to sit over the button. */
    private Location textLocation(Location stationCorner) {
        return stationCorner.clone().add(0.2, 1.1, 0.5);
    }

    private void despawnFor(Player viewer, PackStation station) {
        // Forgotten so a later respawn redraws rather than matching a state
        // this viewer can no longer see.
        Map<PackStation, String> perStation = lastRenderState.get(viewer.getUniqueId());
        if (perStation != null) {
            perStation.remove(station);
        }
        PacketEntityManager.destroyEntity(viewer, station.hitboxEntityId());
        PacketEntityManager.destroyEntity(viewer, station.buttonEntityId());
        PacketEntityManager.destroyEntity(viewer, station.wallEntityId());
        PacketEntityManager.destroyEntity(viewer, station.textEntityId());
    }

    /**
     * The pack's own {@code displayName} carries its own embedded legacy
     * color code (e.g. {@code "&fStarter Pack"}) - spliced directly into the
     * template string here (not passed as an {@code unparsed} placeholder
     * VALUE, which renders raw "&" codes as literal text instead of
     * formatting - see UpgradeStationDisplay#buildText's own note on this).
     */
    private Component buildText(Player viewer, PackStation station) {
        String packId = stationService.currentPackId(station);
        PackDefinition pack = packId != null ? packs.getPackRegistry().find(packId).orElse(null) : null;
        String label = station.isBlackMarket() ? "<#4BD9FF><bold>Black Market</bold></#4BD9FF>\n" : "";

        if (pack == null) {
            return Text.parse(label + "&7Nothing in stock right now");
        }

        String costLine = "&7Cost: &a$<coins>" + (pack.diamondCost() > 0 ? " &8+ &b<diamonds> diamonds" : "");
        String template = label + pack.displayName() + "\n" + costLine + "\n&7Smack to buy!";

        return Text.parse(template,
                Placeholder.unparsed("coins", Formatting.format((double) pack.coinCost())),
                Placeholder.unparsed("diamonds", Formatting.format((double) pack.diamondCost())));
    }
}
