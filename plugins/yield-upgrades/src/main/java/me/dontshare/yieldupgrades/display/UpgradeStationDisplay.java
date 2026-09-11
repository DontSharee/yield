package me.dontshare.yieldupgrades.display;

import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.EntityClickRegistry;
import me.dontshare.yieldcore.packet.InteractionEntityManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import com.github.retrooper.packetevents.util.Vector3f;
import me.dontshare.yieldupgrades.UpgradeService;
import me.dontshare.yieldupgrades.data.UpgradeStation;
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
import java.util.function.Consumer;

/**
 * The packet-visual layer for physical upgrade stations - an invisible
 * clickable {@link InteractionEntityManager} hitbox paired with a visible
 * {@link BlockDisplayManager} button and a floating {@link TextDisplayManager}
 * readout, spawned per-viewer purely by distance (not tied to zone-enter
 * events, to keep this self-contained) - structurally mirrors
 * {@code PetDisplayService}'s own per-viewer visibility loop.
 */
public final class UpgradeStationDisplay {

    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;
    private static final long TICK_INTERVAL = 20L; // 1 second
    /** The button's resting size, as a fraction of a full block - shrunk down so it reads as "a little button," not a full concrete block. */
    private static final float BUTTON_SCALE = 0.35f;
    /** Keeps the shrunk block centered in its cell rather than anchored at the block-display's own origin corner - see BlockDisplayManager#setTransformation. */
    private static final float BUTTON_TRANSLATE = (1f - BUTTON_SCALE) / 2f;
    /**
     * The invisible {@code Interaction} hitbox's own width/height - a touch
     * more generous than the visual button (which is tiny) so it's still
     * easy to click, not pixel-precise.
     */
    private static final float HITBOX_SIZE = 0.8f;
    /** The "push" - briefly shrinks below resting size (depresses inward), then eases back - on every click regardless of outcome, same as a real button giving under your finger. */
    private static final float PUSH_SCALE = BUTTON_SCALE * 0.6f;
    private static final float PUSH_TRANSLATE = (1f - PUSH_SCALE) / 2f;
    private static final int PUSH_TICKS = 3;
    /** The backing plate behind the button - wide/tall (X/Y) but thin (Z, "into the wall") - see #wallLocation for the orientation assumption. */
    private static final float WALL_FACE_SCALE = 0.9f;
    private static final float WALL_DEPTH_SCALE = 0.15f;
    private static final float WALL_FACE_TRANSLATE = (1f - WALL_FACE_SCALE) / 2f;
    private static final float WALL_DEPTH_TRANSLATE = (1f - WALL_DEPTH_SCALE) / 2f;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final UpgradeService upgradeService;
    /** Called after every SUCCESSFUL upgrade, any type - lets the owning plugin re-apply anything derived from an upgrade level outside this class's own concern (e.g. walk speed) without this class needing to know which effect types exist. Cheap/idempotent to call unconditionally. */
    private final Consumer<Player> onUpgradeSuccess;

    private volatile List<UpgradeStation> stations = List.of();
    private final Map<UpgradeStation, Set<UUID>> viewersByStation = new ConcurrentHashMap<>();

    public UpgradeStationDisplay(JavaPlugin plugin, YieldPacks packs, UpgradeService upgradeService, Consumer<Player> onUpgradeSuccess) {
        this.plugin = plugin;
        this.packs = packs;
        this.upgradeService = upgradeService;
        this.onUpgradeSuccess = onUpgradeSuccess;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /**
     * Tears down every currently-spawned station for every viewer and
     * unregisters their click handlers, THEN swaps in the new list and
     * re-registers - a content reload replaces every {@link UpgradeStation}
     * (and its entity ids) wholesale, so nothing from the old list can be
     * reused; without this teardown, the old stations' entities would be
     * orphaned (still visible, click handlers pointing at stale objects)
     * until each viewer happened to wander out of range.
     */
    public void reload(List<UpgradeStation> newStations) {
        for (UpgradeStation station : stations) {
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
        for (UpgradeStation station : newStations) {
            viewersByStation.put(station, ConcurrentHashMap.newKeySet());
            EntityClickRegistry.registerInteract(station.hitboxEntityId(), player -> handleClick(player, station));
        }
    }

    private void tick() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (UpgradeStation station : stations) {
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
                    // Already visible - re-check color every tick too, not just
                    // right after a purchase, since coins earned elsewhere
                    // (a cube kill, a quest claim, ...) can flip a button from
                    // red to green with nothing having happened AT the station.
                    updateColor(viewer, station);
                }
            }
        }
    }

    private void handleClick(Player player, UpgradeStation station) {
        playPushAnimation(player, station);
        UpgradeService.Result result = upgradeService.attemptUpgrade(player, station);
        switch (result) {
            case SUCCESS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
                player.sendMessage(Text.parse("<green>Upgraded <name> to level <level>!</green>",
                        Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(station.type().displayName())),
                        Placeholder.unparsed("level", String.valueOf(upgradeService.levelOf(profile, station.type().id())))));
                refreshTextFor(player, station);
                updateColor(player, station);
                onUpgradeSuccess.accept(player);
            }
            case ALREADY_AT_CAP -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>This station is capped - a later zone raises the ceiling on this upgrade.</red>"));
            }
            case ZONE_LOCKED -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You haven't unlocked this zone.</red>"));
            }
            case CANT_AFFORD -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You can't afford this upgrade yet.</red>"));
            }
        }
    }

    /** Re-renders one station's text for the player who just interacted with it - called right after a successful upgrade so the readout updates the instant it changes, not on the next periodic tick (matches this server's established "update immediately" preference for live stat displays). */
    private void refreshTextFor(Player player, UpgradeStation station) {
        Set<UUID> viewers = viewersByStation.get(station);
        if (viewers != null && viewers.contains(player.getUniqueId())) {
            TextDisplayManager.setText(player, station.textEntityId(), buildText(player, station));
        }
    }

    /** Lime if this viewer could press it right now (affordable, not capped, zone unlocked), red otherwise. */
    private void updateColor(Player viewer, UpgradeStation station) {
        Material color = upgradeService.canAfford(viewer, station) ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        BlockDisplayManager.setBlockState(viewer, station.buttonEntityId(), color);
    }

    /**
     * A quick shrink-then-ease-back-out, independent of whether the upgrade
     * actually succeeded - a real button visibly depresses under your
     * finger whether or not the machine behind it dispenses anything.
     */
    private void playPushAnimation(Player viewer, UpgradeStation station) {
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

    private void spawnFor(Player viewer, UpgradeStation station) {
        Location loc = station.location();

        PacketEntityManager.beginBundle(viewer);
        InteractionEntityManager.spawn(viewer, station.hitboxEntityId(), hitboxLocation(loc));
        InteractionEntityManager.setSize(viewer, station.hitboxEntityId(), HITBOX_SIZE, HITBOX_SIZE);

        BlockDisplayManager.spawn(viewer, station.wallEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, station.wallEntityId(), Material.GRAY_CONCRETE);
        BlockDisplayManager.setTransformation(viewer, station.wallEntityId(),
                new Vector3f(WALL_FACE_TRANSLATE, WALL_FACE_TRANSLATE, WALL_DEPTH_TRANSLATE),
                new Vector3f(WALL_FACE_SCALE, WALL_FACE_SCALE, WALL_DEPTH_SCALE));

        BlockDisplayManager.spawn(viewer, station.buttonEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, station.buttonEntityId(),
                upgradeService.canAfford(viewer, station) ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        BlockDisplayManager.setTransformation(viewer, station.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);

        TextDisplayManager.spawn(viewer, station.textEntityId(), textLocation(loc));
        TextDisplayManager.setBillboard(viewer, station.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, station.textEntityId(), 0x00000000);
        TextDisplayManager.setStyle(viewer, station.textEntityId(), true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setText(viewer, station.textEntityId(), buildText(viewer, station));
        PacketEntityManager.endBundle(viewer);
    }

    /**
     * A {@code BlockDisplayManager}-shrunk block is centered within the
     * 1x1x1 cell whose corner is {@code stationCorner} (translate+scale/2
     * always works out to +0.5 on every axis, regardless of the scale
     * chosen) - i.e. its visual center is {@code stationCorner + (0.5,0.5,0.5)}.
     * <p>
     * Three in-game screenshots of real measured data on the still-
     * unconfirmed {@code Interaction} anchor behavior: inset 0.5 (pure
     * center-style spawn) overshot; inset 0.3 (blended toward center)
     * overshot WORSE than inset 0.1 - i.e. more inset is strictly worse,
     * not a U-shape to interpolate inside. Reverted to inset 0.1 (pure
     * corner-style, {@code (1-size)/2} at this size), the best-measured
     * point so far, rather than guessing a value below it blind. If more
     * precision is wanted later, the next experiment should try LESS
     * inset than 0.1 (toward 0 or negative), not more.
     */
    private Location hitboxLocation(Location stationCorner) {
        double inset = (1.0 - HITBOX_SIZE) / 2.0;
        return stationCorner.clone().add(inset, inset, inset);
    }

    /** Above the button's own top (button spans roughly {@code corner.y+0.325} to {@code corner.y+0.675}) and centered on the same X/Z the button/wall use - previously spawned at the raw, uncentered corner with too little clearance, so it rendered overlapping the button instead of floating above it. */
    private Location textLocation(Location stationCorner) {
        return stationCorner.clone().add(0.5, 1.1, 0.5);
    }

    private void despawnFor(Player viewer, UpgradeStation station) {
        PacketEntityManager.destroyEntity(viewer, station.hitboxEntityId());
        PacketEntityManager.destroyEntity(viewer, station.buttonEntityId());
        PacketEntityManager.destroyEntity(viewer, station.wallEntityId());
        PacketEntityManager.destroyEntity(viewer, station.textEntityId());
    }

    /**
     * "&lt;Upgrade Name&gt;\nLevel &lt;n&gt; / &lt;cap&gt;\n&lt;cost or MAXED&gt;" -
     * level/cost are per-VIEWER (their own progress), so this can't be
     * precomputed once per station. Legacy "&amp;" codes live in the static
     * template (translated by {@link Text#parse} before deserializing) - a
     * placeholder VALUE containing raw "&amp;" codes would render as literal
     * text instead of formatting, so only plain numbers/names are ever
     * substituted in.
     */
    private Component buildText(Player viewer, UpgradeStation station) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(viewer.getUniqueId());
        int level = upgradeService.levelOf(profile, station.type().id());
        int effectiveCap = Math.min(station.cap(), station.type().maxLevel());
        boolean atCap = level >= effectiveCap;

        String template = atCap
                ? "<#4BD9FF><bold><name></bold></#4BD9FF>\n&7Level &f<level> &7/ &f<cap>\n&6&lMAXED AT THIS STATION"
                : "<#4BD9FF><bold><name></bold></#4BD9FF>\n&7Level &f<level> &7/ &f<cap>\n&7Cost: &a$<cost>";

        return Text.parse(template,
                Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(station.type().displayName())),
                Placeholder.unparsed("level", String.valueOf(level)),
                Placeholder.unparsed("cap", String.valueOf(effectiveCap)),
                Placeholder.unparsed("cost", Formatting.format(upgradeService.costFor(station.type(), level))));
    }
}
