package me.dontshare.yieldpackstations.display;

import com.github.retrooper.packetevents.util.Vector3f;
import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.ItemDisplayManager;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The packet-visual layer for physical egg stations - an invisible
 * clickable hitbox, a floating text readout and, where yield-upgrades'
 * {@code UpgradeStationDisplay} puts a little concrete button, a large
 * slowly-turning dragon egg. The egg IS the station: it is the thing you
 * walk up to, the thing you smack, and the thing that vanishes when a
 * hatch begins.
 * <p>
 * Both click types are registered, and they are not the same action:
 * left-click ({@link EntityClickRegistry#register} - the "smack") hatches
 * immediately, holding it down to keep hatching, while right-click
 * ({@link EntityClickRegistry#registerInteract}) opens the hatch menu with
 * the egg's full drop list and the 1x/3x/5x/24x rungs. Smacking stays the
 * fast path on purpose: it is the one a player spends minutes at a time
 * doing.
 */
public final class PackStationDisplay {

    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;
    private static final long TICK_INTERVAL = 20L; // 1 second
    /** Big enough to read as a landmark from across the zone entrance rather than as a decoration on the wall. */
    private static final float EGG_SCALE = 1.6f;
    /** A full turn every this many seconds - slow enough to be ambient, fast enough that the station never looks frozen. */
    private static final int EGG_SPIN_SECONDS = 8;
    /** How long the egg stays gone once a hatch starts, covering the shake-and-crack before it fades back in. */
    private static final long EGG_HIDE_TICKS = 70L;
    // Widened from 0.8 with the egg: the clickable box should cover what a
    // player is actually aiming at, and a 1.6-scale egg is twice the size
    // of the little concrete button this replaced. The INSETS below stay at
    // their empirically-tuned values - the anchor did not move, only the
    // box around it - but both want a look in game.
    private static final float HITBOX_SIZE = 1.6f;
    /** How close a player must stand for the auto-hatch loop to count them as being AT this station. */
    private static final double HATCH_SITE_RANGE_SQUARED = 6.0 * 6.0;
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
    private static final float PUSH_SCALE = EGG_SCALE * 0.82f;
    private static final int PUSH_TICKS = 3;
    private static final float WALL_FACE_SCALE = 0.9f;
    private static final float WALL_DEPTH_SCALE = 0.15f;
    private static final float WALL_FACE_TRANSLATE = (1f - WALL_FACE_SCALE) / 2f;
    private static final float WALL_DEPTH_TRANSLATE = (1f - WALL_DEPTH_SCALE) / 2f;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final PackStationService stationService;

    private volatile List<PackStation> stations = List.of();
    /** Drives the ambient spin - seconds since start, since the display tick is one per second. */
    private int tickCount;
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
            EntityClickRegistry.unregisterInteract(station.hitboxEntityId());
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
            EntityClickRegistry.register(station.hitboxEntityId(), player -> handleSmack(player, station));
            EntityClickRegistry.registerInteract(station.hitboxEntityId(), player -> handleRightClick(player, station));
        }
    }

    private void tick() {
        int ticksElapsed = tickCount++;
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
                    // Re-render every tick, not just after a hatch - a
                    // black-market station's live pack/cost can change
                    // mid-view the instant its rotation flips, and a coin
                    // balance can change from something unrelated too.
                    refreshFor(viewer, station);
                    spinEgg(viewer, station, ticksElapsed);
                }
            }
        }
    }

    /**
     * Left-click: hatch now. Sneak to hatch as many as this player's tier
     * allows and can afford.
     * <p>
     * A BUSY result is silent on purpose. Smacking is a hold-down action, so
     * the cooldown refuses several times a second while a player leans on
     * the button, and saying so each time would be a wall of red text.
     */
    private void handleSmack(Player player, PackStation station) {
        playPushAnimation(player, station);
        PackStationService.Purchase purchase = stationService.attemptHatch(player, station, player.isSneaking());
        switch (purchase.result()) {
            case SUCCESS -> hideEggDuringHatch(player, station);
            case BUSY -> {
            }
            case ZONE_LOCKED -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You haven't unlocked this zone.</red>"));
            }
            case CANT_AFFORD -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You can't afford this egg yet.</red>"));
            }
            case NO_STOCK_CONFIGURED -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>This station has nothing in it right now.</red>"));
            }
        }
    }

    /** Right-click: the hatch menu - every pet this egg can drop, at this player's own odds, and the 1x/3x/5x/24x rungs. */
    private void handleRightClick(Player player, PackStation station) {
        String packId = stationService.currentPackId(station);
        if (packId == null) {
            player.sendMessage(Text.parse("<red>This station has nothing in it right now.</red>"));
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.6f);
        packs.getHatchMenuGui().open(player, packId);
    }

    /**
     * The station's own egg vanishes for the length of the hatch, so the
     * only eggs on screen are the ones actually cracking open in front of
     * the player - then fades back, ready for the next one.
     */
    private void hideEggDuringHatch(Player player, PackStation station) {
        PacketEntityManager.destroyEntity(player, station.buttonEntityId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            Set<UUID> viewers = viewersByStation.get(station);
            if (viewers == null || !viewers.contains(player.getUniqueId())) {
                return;
            }
            spawnEgg(player, station);
        }, EGG_HIDE_TICKS);
    }

    /** Whichever station this player is standing at, or null - the answer PackOpenService's auto-hatch loop asks for. */
    public String hatchSiteFor(Player player) {
        for (PackStation station : stations) {
            if (!player.getWorld().equals(station.location().getWorld())) {
                continue;
            }
            if (station.location().distanceSquared(player.getLocation()) <= HATCH_SITE_RANGE_SQUARED) {
                return stationService.currentPackId(station);
            }
        }
        return null;
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
        // A black-market station's egg changes with its rotation, so the
        // display has to follow it, not just the sign above it.
        spawnEgg(viewer, station);
    }

    /**
     * Turns every visible egg a little further round, interpolated across
     * the whole second until the next step, so a station is always gently
     * moving. Runs off the same once-a-second tick everything else here
     * does - a genuinely smooth spin would need a packet per tick per
     * viewer per station, which is twenty times the traffic for an effect
     * nobody is staring at.
     */
    private void spinEgg(Player viewer, PackStation station, int secondsElapsed) {
        float yaw = (secondsElapsed % EGG_SPIN_SECONDS) * (360f / EGG_SPIN_SECONDS);
        ItemDisplayManager.setInterpolation(viewer, station.buttonEntityId(), 0, (int) TICK_INTERVAL, (int) TICK_INTERVAL);
        ItemDisplayManager.setRotation(viewer, station.buttonEntityId(), 0f, yaw);
    }

    /** The egg squashes when smacked and springs back - the whole feedback a hold-down player gets, since the refusals are silent. */
    private void playPushAnimation(Player viewer, PackStation station) {
        ItemDisplayManager.setInterpolation(viewer, station.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
        ItemDisplayManager.setScale(viewer, station.buttonEntityId(), PUSH_SCALE, PUSH_SCALE, PUSH_SCALE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            ItemDisplayManager.setInterpolation(viewer, station.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
            ItemDisplayManager.setScale(viewer, station.buttonEntityId(), EGG_SCALE, EGG_SCALE, EGG_SCALE);
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

        spawnEgg(viewer, station);

        TextDisplayManager.spawn(viewer, station.textEntityId(), textLocation(loc));
        TextDisplayManager.setBillboard(viewer, station.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, station.textEntityId(), 0x00000000);
        TextDisplayManager.setStyle(viewer, station.textEntityId(), true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setText(viewer, station.textEntityId(), buildText(viewer, station));
        PacketEntityManager.endBundle(viewer);
    }

    /**
     * The egg wears its own pack definition's HeadDatabase head, falling
     * back to that pack's material - so a zone can have its own egg rather
     * than every station being the same prop with a different sign over it,
     * and a server without HeadDatabase still shows a real (vanilla) egg.
     */
    private void spawnEgg(Player viewer, PackStation station) {
        PackDefinition pack = packs.getPackRegistry().find(stationService.currentPackId(station)).orElse(null);
        ItemStack egg = pack != null
                ? packs.getIconFactory().headOrFallback(pack.headDatabaseId(), pack.material())
                : new ItemStack(Material.DRAGON_EGG);
        ItemDisplayManager.spawn(viewer, station.buttonEntityId(), eggLocation(station.location()));
        ItemDisplayManager.setItem(viewer, station.buttonEntityId(), egg);
        ItemDisplayManager.setScale(viewer, station.buttonEntityId(), EGG_SCALE, EGG_SCALE, EGG_SCALE);
    }

    /** Floats the egg off the pedestal so it reads as an object on display rather than a block stuck to the wall. */
    private Location eggLocation(Location stationCorner) {
        return stationCorner.clone().add(0.5, 0.75, 0.5);
    }

    /** See HITBOX_INSET's own comment - same corner-anchored Interaction quirk as UpgradeStationDisplay#hitboxLocation, tuned independently. */
    private Location hitboxLocation(Location stationCorner) {
        return stationCorner.clone().add(HITBOX_INSET_X, HITBOX_INSET_Y, HITBOX_INSET_Z);
    }

    /**
     * X pulled back from the cell-center 0.5 - in-game feedback was that the
     * text floated above the raw hitbox corner rather than above the button
     * itself, so it needed pushing toward -X. Y raised from 1.1 to clear the
     * egg, which at {@value #EGG_SCALE} scale stands about a block and a
     * half tall from its own centre at 0.75 and would otherwise have the
     * readout buried inside it.
     */
    private Location textLocation(Location stationCorner) {
        return stationCorner.clone().add(0.2, 2.0, 0.5);
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
        String template = label + pack.displayName() + "\n" + costLine
                + "\n&7Smack to hatch &8| &7Sneak-smack for many"
                + "\n&7Right-click for drops";

        return Text.parse(template,
                Placeholder.unparsed("coins", Formatting.format((double) pack.coinCost())),
                Placeholder.unparsed("diamonds", Formatting.format((double) pack.diamondCost())));
    }
}
