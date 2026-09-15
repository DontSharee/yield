package me.dontshare.yieldzonemachines.display;

import com.github.retrooper.packetevents.util.Vector3f;
import me.dontshare.yieldcore.packet.BlockDisplayManager;
import me.dontshare.yieldcore.packet.EntityClickRegistry;
import me.dontshare.yieldcore.packet.InteractionEntityManager;
import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldrebirth.RebirthService;
import me.dontshare.yieldzonemachines.ZoneMachineService;
import me.dontshare.yieldzonemachines.data.ZoneMachine;
import me.dontshare.yieldzonemachines.data.ZoneMachineType;
import me.dontshare.yieldzonemachines.gui.CandyApplyGui;
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
 * The packet-visual layer for physical zone machines - near-identical to
 * yield-packstations' own {@code PackStationDisplay} (same invisible
 * clickable hitbox + visible button + floating text readout, spawned
 * per-viewer purely by distance), including its own hitbox-inset/text-offset
 * tuning (carried over as a starting point since it's the exact same
 * Interaction/BlockDisplay/TextDisplay combination behind both). Registers
 * via {@link EntityClickRegistry#register} (left-click/attack - "smack"),
 * matching the pack stations' own convention rather than the upgrade
 * stations' right-click, since these are purchases too.
 */
public final class ZoneMachineDisplay {

    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;
    private static final long TICK_INTERVAL = 20L; // 1 second
    private static final float BUTTON_SCALE = 0.35f;
    private static final float BUTTON_TRANSLATE = (1f - BUTTON_SCALE) / 2f;
    private static final float HITBOX_SIZE = 0.8f;
    // Same starting point as PackStationDisplay's own dialed-in values (three
    // rounds of in-game screenshot feedback there) - the exact same
    // Interaction-anchor quirk applies here, so this is an informed guess,
    // not the original broken (1-size)/2 formula.
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
    private final RebirthService rebirthService;
    private final ZoneMachineService machineService;
    private final CandyApplyGui candyApplyGui;

    private volatile List<ZoneMachine> machines = List.of();
    private final Map<ZoneMachine, Set<UUID>> viewersByMachine = new ConcurrentHashMap<>();
    /** What each viewer was last actually sent per machine - see {@link #refreshFor}. */
    private final Map<UUID, Map<ZoneMachine, Rendered>> lastRendered = new ConcurrentHashMap<>();

    public ZoneMachineDisplay(JavaPlugin plugin, YieldPacks packs, RebirthService rebirthService,
                               ZoneMachineService machineService, CandyApplyGui candyApplyGui) {
        this.plugin = plugin;
        this.packs = packs;
        this.rebirthService = rebirthService;
        this.machineService = machineService;
        this.candyApplyGui = candyApplyGui;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /** Same teardown-then-rebuild reasoning as PackStationDisplay#reload - a content reload replaces every machine (and its entity ids) wholesale. */
    public void reload(List<ZoneMachine> newMachines) {
        for (ZoneMachine machine : machines) {
            EntityClickRegistry.unregister(machine.hitboxEntityId());
            Set<UUID> viewers = viewersByMachine.remove(machine);
            if (viewers == null) {
                continue;
            }
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    despawnFor(viewer, machine);
                }
            }
        }
        machines = newMachines;
        for (ZoneMachine machine : newMachines) {
            viewersByMachine.put(machine, ConcurrentHashMap.newKeySet());
            EntityClickRegistry.register(machine.hitboxEntityId(), player -> handleClick(player, machine));
        }
    }

    private void tick() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (ZoneMachine machine : machines) {
                Set<UUID> viewers = viewersByMachine.get(machine);
                if (viewers == null) {
                    continue;
                }
                boolean inRange = viewer.getWorld().equals(machine.location().getWorld())
                        && machine.location().distanceSquared(viewer.getLocation()) <= VIEW_DISTANCE_SQUARED;
                boolean seeing = viewers.contains(viewer.getUniqueId());
                if (inRange && !seeing) {
                    spawnFor(viewer, machine);
                    viewers.add(viewer.getUniqueId());
                } else if (!inRange && seeing) {
                    despawnFor(viewer, machine);
                    viewers.remove(viewer.getUniqueId());
                } else if (inRange) {
                    // Re-render every tick, not just after a use - a coin
                    // balance (or a rebirth's own live preview) can change
                    // from something entirely unrelated to this machine.
                    refreshFor(viewer, machine);
                }
            }
        }
    }

    private void handleClick(Player player, ZoneMachine machine) {
        playPushAnimation(player, machine);
        ZoneMachineService.UseResult result = machineService.attemptUse(player, machine);
        switch (result.result()) {
            case SUCCESS -> {
                if (machine.type() == ZoneMachineType.CANDY_APPLY) {
                    // The actual feeding happens inside CandyApplyGui itself
                    // (its own sound/message play once a candy is actually
                    // dropped in) - a SUCCESS here just means "go ahead and open it".
                    candyApplyGui.open(player);
                    return;
                }
                playSuccessSound(player, machine);
                player.sendMessage(successMessage(machine, result.detail()));
                refreshFor(player, machine);
            }
            case ZONE_LOCKED -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You haven't unlocked this zone.</red>"));
            }
            case CANT_AFFORD -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You can't afford this yet.</red>"));
            }
            case NO_EQUIPPED_PETS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You don't have any pets equipped to feed.</red>"));
            }
        }
    }

    /**
     * REBIRTH plays nothing here on purpose - RebirthService#rebirth already
     * fires RebirthEvent, which RebirthFanfareListener turns into its own
     * dedicated fanfare (dragon growl + firework), so adding a second sound
     * here would just stack awkwardly on top of it. CANDY_APPLY never
     * reaches here at all (see {@link #handleClick}) - its own sound plays
     * from inside {@code CandyApplyGui} once a candy is actually fed.
     */
    private void playSuccessSound(Player player, ZoneMachine machine) {
        switch (machine.type()) {
            case CANDY_APPLY -> {
                // Unreachable - see javadoc above.
            }
            case REBIRTH -> {
                // Handled by RebirthFanfareListener instead - see javadoc above.
            }
        }
    }

    private Component successMessage(ZoneMachine machine, String detail) {
        return switch (machine.type()) {
            case REBIRTH -> Text.parse("<green>Rebirthed <detail>x!</green>", Placeholder.unparsed("detail", detail));
            case CANDY_APPLY -> Component.empty(); // Unreachable - see handleClick.
        };
    }

    private void refreshFor(Player viewer, ZoneMachine machine) {
        Set<UUID> viewers = viewersByMachine.get(machine);
        if (viewers == null || !viewers.contains(viewer.getUniqueId())) {
            return;
        }
        // Runs for every in-range viewer of every machine once a second, and
        // a rebirth readout only actually changes when the player's coins
        // cross a threshold - so the two packets go out when what they carry
        // differs from what this viewer was last sent, not on every pass.
        Component text = buildText(viewer, machine);
        Material color = machineService.canUse(viewer, machine) ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        Rendered previous = lastRendered
                .computeIfAbsent(viewer.getUniqueId(), id -> new ConcurrentHashMap<>())
                .put(machine, new Rendered(text, color));
        if (previous != null && previous.color() == color && previous.text().equals(text)) {
            return;
        }
        TextDisplayManager.setText(viewer, machine.textEntityId(), text);
        BlockDisplayManager.setBlockState(viewer, machine.buttonEntityId(), color);
    }

    /** What one viewer was last actually shown for one machine. */
    private record Rendered(Component text, Material color) {
    }

    private void playPushAnimation(Player viewer, ZoneMachine machine) {
        BlockDisplayManager.setInterpolation(viewer, machine.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
        BlockDisplayManager.setTransformation(viewer, machine.buttonEntityId(), PUSH_TRANSLATE, PUSH_SCALE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            BlockDisplayManager.setInterpolation(viewer, machine.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
            BlockDisplayManager.setTransformation(viewer, machine.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);
        }, PUSH_TICKS);
    }

    private void spawnFor(Player viewer, ZoneMachine machine) {
        Location loc = machine.location();

        PacketEntityManager.beginBundle(viewer);
        InteractionEntityManager.spawn(viewer, machine.hitboxEntityId(), hitboxLocation(loc));
        InteractionEntityManager.setSize(viewer, machine.hitboxEntityId(), HITBOX_SIZE, HITBOX_SIZE);

        BlockDisplayManager.spawn(viewer, machine.wallEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, machine.wallEntityId(), wallMaterial(machine));
        BlockDisplayManager.setTransformation(viewer, machine.wallEntityId(),
                new Vector3f(WALL_FACE_TRANSLATE, WALL_FACE_TRANSLATE, WALL_DEPTH_TRANSLATE),
                new Vector3f(WALL_FACE_SCALE, WALL_FACE_SCALE, WALL_DEPTH_SCALE));

        BlockDisplayManager.spawn(viewer, machine.buttonEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, machine.buttonEntityId(),
                machineService.canUse(viewer, machine) ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        BlockDisplayManager.setTransformation(viewer, machine.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);

        TextDisplayManager.spawn(viewer, machine.textEntityId(), textLocation(loc));
        TextDisplayManager.setBillboard(viewer, machine.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, machine.textEntityId(), 0x00000000);
        TextDisplayManager.setStyle(viewer, machine.textEntityId(), true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setText(viewer, machine.textEntityId(), buildText(viewer, machine));
        PacketEntityManager.endBundle(viewer);
    }

    /** A distinct wall color per machine type, so the two read apart from a distance the same way the black market's own black wall already reads apart from a normal pack station's gray one. */
    private Material wallMaterial(ZoneMachine machine) {
        return switch (machine.type()) {
            case REBIRTH -> Material.PURPLE_CONCRETE;
            case CANDY_APPLY -> Material.MAGENTA_CONCRETE;
        };
    }

    private Location hitboxLocation(Location stationCorner) {
        return stationCorner.clone().add(HITBOX_INSET_X, HITBOX_INSET_Y, HITBOX_INSET_Z);
    }

    private Location textLocation(Location stationCorner) {
        return stationCorner.clone().add(0.2, 1.1, 0.5);
    }

    private void despawnFor(Player viewer, ZoneMachine machine) {
        Map<ZoneMachine, Rendered> perMachine = lastRendered.get(viewer.getUniqueId());
        if (perMachine != null) {
            perMachine.remove(machine);
        }
        PacketEntityManager.destroyEntity(viewer, machine.hitboxEntityId());
        PacketEntityManager.destroyEntity(viewer, machine.buttonEntityId());
        PacketEntityManager.destroyEntity(viewer, machine.wallEntityId());
        PacketEntityManager.destroyEntity(viewer, machine.textEntityId());
    }

    private Component buildText(Player viewer, ZoneMachine machine) {
        return switch (machine.type()) {
            case REBIRTH -> rebirthText(viewer);
            case CANDY_APPLY -> candyApplyText();
        };
    }

    private Component candyApplyText() {
        return Text.parse("<#FF66DD><bold>Candy Apply</bold></#FF66DD>\n&7Smack to open -\n&7drag a candy in to feed it.");
    }

    private Component rebirthText(Player viewer) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(viewer.getUniqueId());
        RebirthService.RebirthPreview preview = rebirthService.preview(profile);
        return Text.parse(
                "<#B15CFF><bold>Rebirth Machine</bold></#B15CFF>\n&7Available: &f<n>\n&7Cost: &a$<cost>\n&7Smack to Rebirth!",
                Placeholder.unparsed("n", Formatting.format((double) preview.available())),
                Placeholder.unparsed("cost", Formatting.format(preview.totalCost())));
    }
}
