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
import me.dontshare.yieldpacks.gui.PackOddsLore;
import me.dontshare.yieldpackstations.PackStationService;
import me.dontshare.yieldpackstations.data.PackStation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
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
 * dragon egg. The egg IS the station: it is the thing you walk up to, the
 * thing you smack, and the thing that vanishes when a hatch begins.
 * <p>
 * It does not turn. An earlier pass span it slowly for ambience, which
 * fought the squash-on-smack animation for the same rotation channel and
 * made a fixed landmark read as something that had just spawned. A big
 * still egg looks placed; a turning one looks dropped.
 * <p>
 * Both click types are registered, and they are not the same action:
 * left-click ({@link EntityClickRegistry#register} - the "smack") hatches
 * immediately, holding it down to keep hatching, while right-click
 * ({@link EntityClickRegistry#registerInteract}) opens the hatch menu with
 * the egg's full drop list and the 1x/3x/5x/24x rungs. Smacking stays the
 * fast path on purpose: it is the one a player spends minutes at a time
 * doing.
 */
public final class PackStationDisplay implements Listener {

    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;
    private static final long TICK_INTERVAL = 20L; // 1 second
    /** Big enough to read as a landmark from across the zone entrance rather than as a decoration on the wall. */
    private static final float EGG_SCALE = 2.4f;
    /** How long the egg stays gone once a hatch starts, covering the shake-and-crack before it fades back in. */
    private static final long EGG_HIDE_TICKS = 70L;
    // Widened from 0.8 with the egg: the clickable box should cover what a
    // player is actually aiming at, and a 1.6-scale egg is twice the size
    // of the little concrete button this replaced. The INSETS below stay at
    // their empirically-tuned values - the anchor did not move, only the
    // box around it - but both want a look in game.
    private static final float HITBOX_SIZE = 2.4f;
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
    private final Map<PackStation, Set<UUID>> viewersByStation = new ConcurrentHashMap<>();
    /** What each viewer was last actually shown per station, so an unchanged sign costs nothing - see {@link #refreshFor}. */
    private final Map<UUID, Map<PackStation, String>> lastRenderState = new ConcurrentHashMap<>();
    /** Which egg is currently ON each viewer's screen per station, so a rotation swaps the item instead of respawning the entity. */
    private final Map<UUID, Map<PackStation, String>> shownEggPackId = new ConcurrentHashMap<>();
    /** When each viewer's hidden egg is due back, per station - see {@link #hideEggDuringHatch}. */
    private final Map<UUID, Map<PackStation, Long>> eggHiddenUntil = new ConcurrentHashMap<>();

    /**
     * The "click to disable auto hatch" bar each station can show: a glowing
     * red slab on the floor in front of the egg, its label above it, and a
     * hitbox to smack. Only the player auto-hatching at THAT station sees it.
     */
    private record Bar(int bodyId, int hitboxId, int textId) {
    }
    private final Map<PackStation, Bar> bars = new ConcurrentHashMap<>();
    /** Which station's bar each player currently has on screen. */
    private final Map<UUID, PackStation> barShownFor = new ConcurrentHashMap<>();
    private static final float BAR_WIDTH = 1.6f;
    private static final float BAR_THICKNESS = 0.22f;
    /** How far in front of the station the bar sits - clear of the egg. */
    private static final double BAR_OUT = 1.4;
    private static final int BAR_GLOW = 0xFF3B3B;

    public PackStationDisplay(JavaPlugin plugin, YieldPacks packs, PackStationService stationService) {
        this.plugin = plugin;
        this.packs = packs;
        this.stationService = stationService;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /**
     * Forgets a player who logged off. Their client dropped every packet
     * entity with them, so on the way back in each station has to count them
     * as a new viewer and spawn from scratch - left in the viewer sets, a
     * rejoining player stood in front of stations that were never re-sent.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        for (Set<UUID> viewers : viewersByStation.values()) {
            viewers.remove(id);
        }
        lastRenderState.remove(id);
        shownEggPackId.remove(id);
        eggHiddenUntil.remove(id);
        barShownFor.remove(id);
    }

    /** Same teardown-then-rebuild reasoning as UpgradeStationDisplay#reload - a content reload replaces every PackStation (and its entity ids) wholesale. */
    public void reload(List<PackStation> newStations) {
        for (PackStation station : stations) {
            EntityClickRegistry.unregister(station.hitboxEntityId());
            EntityClickRegistry.unregisterInteract(station.hitboxEntityId());
            Bar oldBar = bars.remove(station);
            if (oldBar != null) {
                EntityClickRegistry.unregister(oldBar.hitboxId());
                EntityClickRegistry.unregisterInteract(oldBar.hitboxId());
            }
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
            Bar bar = new Bar(PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId(), PacketEntityManager.nextEntityId());
            bars.put(station, bar);
            EntityClickRegistry.register(bar.hitboxId(), player -> handleBarSmack(player, station));
            EntityClickRegistry.registerInteract(bar.hitboxId(), player -> handleBarSmack(player, station));
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
                    // Re-render every tick, not just after a hatch - a
                    // black-market station's live pack/cost can change
                    // mid-view the instant its rotation flips, and a coin
                    // balance can change from something unrelated too.
                    refreshFor(viewer, station);
                    updateEgg(viewer, station);
                    updateBar(viewer, station);
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
            case BAG_FULL -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                var storage = packs.getBagStorageService();
                player.sendMessage(Text.parse("<red><msg></red>", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("msg",
                        storage.fullMessage(player, packs.getPlayerStore().getOrCreate(player.getUniqueId())))));
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
     * the player - then comes back, ready for the next one.
     * <p>
     * Recorded as a due-time the display tick honours rather than scheduling
     * its own respawn, because a player holding down the smack hides it
     * several times a second: one task per hide would race a pile of
     * respawns against each other, while a due-time just moves further out.
     */
    private void hideEggDuringHatch(Player player, PackStation station) {
        eggHiddenUntil.computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>())
                .put(station, System.currentTimeMillis() + EGG_HIDE_TICKS * 50L);
        PacketEntityManager.destroyEntity(player, station.buttonEntityId());
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

    /** Where the station {@link #hatchSiteFor} would pick stands - what the auto-hatch leash is measured from. */
    public Location hatchSiteLocation(Player player) {
        for (PackStation station : stations) {
            if (player.getWorld().equals(station.location().getWorld())
                    && station.location().distanceSquared(player.getLocation()) <= HATCH_SITE_RANGE_SQUARED) {
                return station.location();
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
    }

    /**
     * The egg's own once-a-second upkeep, kept out of {@link #refreshFor}
     * because the two react to different things. The sign redraws whenever
     * the price or this viewer's ability to pay it changes; the egg only
     * cares WHICH egg it is.
     * <p>
     * Folding it into the sign's refresh was a bug: affordability flips
     * every time a player hatches and earns it back, which was respawning
     * the whole display entity several times a second - resetting its spin,
     * stepping on the squash animation, and resurrecting an egg that was
     * deliberately hidden for the length of a hatch.
     *
     * @return whether the egg is on screen and worth spinning.
     */
    private boolean updateEgg(Player viewer, PackStation station) {
        Map<PackStation, Long> hiddenPerStation = eggHiddenUntil.get(viewer.getUniqueId());
        Long hiddenUntil = hiddenPerStation != null ? hiddenPerStation.get(station) : null;
        if (hiddenUntil != null) {
            if (System.currentTimeMillis() < hiddenUntil) {
                return false;
            }
            // The hatch is over. A timer per hide would have stacked one
            // respawn per smack; the tick that was already running brings it
            // back exactly once however many times it was hidden.
            hiddenPerStation.remove(station);
            spawnEgg(viewer, station);
            return true;
        }

        String packId = stationService.currentPackId(station);
        Map<PackStation, String> shown = shownEggPackId.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>());
        String current = shown.get(station);
        if (packId != null && !packId.equals(current)) {
            // A black market rotation. The entity is already there, so this
            // is one metadata packet rather than a despawn/respawn pair.
            shown.put(station, packId);
            ItemDisplayManager.setItem(viewer, station.buttonEntityId(), eggItem(station));
        }
        return true;
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
                station.isDynamic() ? Material.BLACK_CONCRETE : Material.GRAY_CONCRETE);
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
        ItemDisplayManager.spawn(viewer, station.buttonEntityId(), eggLocation(station.location()));
        ItemDisplayManager.setItem(viewer, station.buttonEntityId(), eggItem(station));
        ItemDisplayManager.setScale(viewer, station.buttonEntityId(), EGG_SCALE, EGG_SCALE, EGG_SCALE);
        // Square-on to whoever is looking at the station's front, set once
        // and never touched again - see this class's note on not spinning.
        ItemDisplayManager.setRotation(viewer, station.buttonEntityId(), 0f, station.location().getYaw());
        String packId = stationService.currentPackId(station);
        if (packId != null) {
            shownEggPackId.computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>()).put(station, packId);
        }
    }

    private ItemStack eggItem(PackStation station) {
        PackDefinition pack = packs.getPackRegistry().find(stationService.currentPackId(station)).orElse(null);
        return pack != null
                ? packs.getIconFactory().headOrFallback(pack.headDatabaseId(), pack.material())
                : new ItemStack(Material.DRAGON_EGG);
    }

    /** Floats the egg off the pedestal so it reads as an object on display rather than a block stuck to the wall. */
    private Location eggLocation(Location stationCorner) {
        return stationCorner.clone().add(0.5, 1.0, 0.5);
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
        return stationCorner.clone().add(0.2, 2.7, 0.5);
    }

    /** Shows the bar at the station this viewer is auto-hatching at, and takes it away once they aren't. */
    private void updateBar(Player viewer, PackStation station) {
        Location site = packs.getPackOpenService().autoHatchSite(viewer);
        boolean here = site != null && site.getWorld().equals(station.location().getWorld())
                && site.distanceSquared(station.location()) < 0.01;
        PackStation shown = barShownFor.get(viewer.getUniqueId());
        if (here && shown != station) {
            if (shown != null) {
                despawnBar(viewer, shown);
            }
            spawnBar(viewer, station);
            barShownFor.put(viewer.getUniqueId(), station);
        } else if (!here && shown == station) {
            despawnBar(viewer, station);
            barShownFor.remove(viewer.getUniqueId());
        }
    }

    /** One smack on the bar: auto hatch off, the bar presses in and goes. */
    private void handleBarSmack(Player player, PackStation station) {
        if (barShownFor.get(player.getUniqueId()) != station) {
            return;
        }
        barShownFor.remove(player.getUniqueId());
        packs.getPackOpenService().stopAutoHatch(player);
        Bar bar = bars.get(station);
        if (bar != null) {
            Vector3f[] shape = barShape(station, 0.8f);
            BlockDisplayManager.setInterpolation(player, bar.bodyId(), 0, PUSH_TICKS, PUSH_TICKS);
            BlockDisplayManager.setTransformation(player, bar.bodyId(), shape[0], shape[1]);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && barShownFor.get(player.getUniqueId()) != station) {
                    despawnBar(player, station);
                }
            }, PUSH_TICKS + 1L);
        }
        player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 0.7f, 0.8f);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.7f);
        player.sendMessage(Text.parse("<gray>Auto Hatch <red>off</red>.</gray>"));
    }

    private void spawnBar(Player viewer, PackStation station) {
        Bar bar = bars.get(station);
        if (bar == null) {
            return;
        }
        Location center = barCenter(station);
        Vector3f[] shape = barShape(station, 1f);

        PacketEntityManager.beginBundle(viewer);
        BlockDisplayManager.spawn(viewer, bar.bodyId(), center);
        BlockDisplayManager.setBlockState(viewer, bar.bodyId(), Material.RED_CONCRETE);
        BlockDisplayManager.setTransformation(viewer, bar.bodyId(), shape[0], shape[1]);
        ItemDisplayManager.setGlowing(viewer, bar.bodyId(), true);
        ItemDisplayManager.setGlowColor(viewer, bar.bodyId(), BAR_GLOW);

        // Interaction boxes stand on their bottom face - from the floor up past the bar.
        InteractionEntityManager.spawn(viewer, bar.hitboxId(), center.clone().subtract(0, center.getY() - station.location().getY(), 0));
        InteractionEntityManager.setSize(viewer, bar.hitboxId(), BAR_WIDTH, 0.6f);

        TextDisplayManager.spawn(viewer, bar.textId(), center.clone().add(0, 0.3, 0));
        TextDisplayManager.setBillboard(viewer, bar.textId(), TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, bar.textId(), 0x00000000);
        TextDisplayManager.setStyle(viewer, bar.textId(), true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setScale(viewer, bar.textId(), 0.8f, 0.8f, 0.8f);
        TextDisplayManager.setText(viewer, bar.textId(),
                Text.parse("&c&l" + Formatting.fancyFont("Click to disable Auto Hatch")));
        PacketEntityManager.endBundle(viewer);
    }

    private void despawnBar(Player viewer, PackStation station) {
        Bar bar = bars.get(station);
        if (bar == null) {
            return;
        }
        PacketEntityManager.destroyEntity(viewer, bar.bodyId());
        PacketEntityManager.destroyEntity(viewer, bar.hitboxId());
        PacketEntityManager.destroyEntity(viewer, bar.textId());
    }

    /** On the floor in front of the station - "front" being the way its egg faces. */
    private Location barCenter(PackStation station) {
        Location corner = station.location();
        double yaw = Math.toRadians(corner.getYaw());
        return new Location(corner.getWorld(),
                corner.getX() + 0.5 - Math.sin(yaw) * BAR_OUT,
                corner.getY() + 0.15,
                corner.getZ() + 0.5 + Math.cos(yaw) * BAR_OUT);
    }

    /**
     * The bar's translate/scale, centred on {@link #barCenter} and lying
     * across the station's front - along X for a station facing north or
     * south, along Z for one facing east or west. {@code press} squashes it
     * for the push animation.
     */
    private Vector3f[] barShape(PackStation station, float press) {
        double yaw = Math.toRadians(station.location().getYaw());
        boolean acrossX = Math.abs(Math.cos(yaw)) >= Math.abs(Math.sin(yaw));
        float width = BAR_WIDTH * (press < 1f ? 0.95f : 1f);
        float height = BAR_THICKNESS * press;
        float sx = acrossX ? width : BAR_THICKNESS;
        float sz = acrossX ? BAR_THICKNESS : width;
        return new Vector3f[] {
                new Vector3f(-sx / 2f, -height / 2f, -sz / 2f),
                new Vector3f(sx, height, sz)
        };
    }

    private void despawnFor(Player viewer, PackStation station) {
        if (barShownFor.get(viewer.getUniqueId()) == station) {
            barShownFor.remove(viewer.getUniqueId());
            despawnBar(viewer, station);
        }
        // Forgotten so a later respawn redraws rather than matching a state
        // this viewer can no longer see.
        Map<PackStation, String> perStation = lastRenderState.get(viewer.getUniqueId());
        if (perStation != null) {
            perStation.remove(station);
        }
        Map<PackStation, String> shown = shownEggPackId.get(viewer.getUniqueId());
        if (shown != null) {
            shown.remove(station);
        }
        Map<PackStation, Long> hidden = eggHiddenUntil.get(viewer.getUniqueId());
        if (hidden != null) {
            hidden.remove(station);
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
        String dynamicLabel = stationService.dynamicLabel(station);
        String label = dynamicLabel.isEmpty() ? "" : dynamicLabel + "\n";

        if (pack == null) {
            return Text.parse(label + "&7Nothing in stock right now");
        }

        String costLine = "&7Cost: " + stationService.priceLabel(station);
        String template = label + pack.displayName() + "\n" + costLine
                + "\n&7Smack to hatch &8| &7Sneak-smack for many"
                + "\n&7Right-click for drops";

        return Text.parse(template);
    }
}
