package me.dontshare.yieldspawnnpcs.crate;

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
 * The packet-visual layer for each Crate's physical station - same
 * invisible-hitbox + visible-button + floating-text shape every other
 * clickable station in this codebase uses (see yield-packs'
 * PetEnchantTableDisplay), global and not zone-gated. The one thing that's
 * different: the floating text is genuinely per-viewer content (their OWN
 * Key count for this specific crate), so it's rebuilt on a slower cadence
 * for everyone already looking at it, not just once on spawn - otherwise a
 * key found mid-farming wouldn't show up until the player walked away and back.
 */
public final class CrateDisplay implements org.bukkit.event.Listener {

    private static final double VIEW_DISTANCE_SQUARED = 48.0 * 48.0;
    private static final long TICK_INTERVAL = 20L; // 1 second - spawn/despawn-by-distance check
    private static final long TEXT_REFRESH_EVERY_N_TICKS = 5; // every 5th tick cycle (~5s) - keeps each viewer's own key count live without resending every second
    private static final float BUTTON_SCALE = 0.6f;
    private static final float BUTTON_TRANSLATE = (1f - BUTTON_SCALE) / 2f;
    private static final float HITBOX_SIZE = 0.9f;
    private static final float HITBOX_INSET_Y = 0.1f;
    private static final float PUSH_SCALE = BUTTON_SCALE * 0.85f;
    private static final float PUSH_TRANSLATE = (1f - PUSH_SCALE) / 2f;
    private static final int PUSH_TICKS = 3;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final CrateService crateService;

    private volatile List<CrateDefinition> crates = List.of();
    private final Map<CrateDefinition, Set<UUID>> viewersByCrate = new ConcurrentHashMap<>();
    private long tickCount;

    public CrateDisplay(JavaPlugin plugin, YieldPacks packs, CrateService crateService) {
        this.plugin = plugin;
        this.packs = packs;
        this.crateService = crateService;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /**
     * Forgets a player who logged off: their client dropped every packet
     * entity, so on rejoin each crate must count them as a new viewer and
     * spawn again - left in, it never reappeared until they walked away
     * and back.
     */
    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        java.util.UUID id = event.getPlayer().getUniqueId();
        for (java.util.Set<java.util.UUID> viewers : viewersByCrate.values()) {
            viewers.remove(id);
        }
    }

    /** A content reload replaces every crate (and its entity ids) wholesale - same teardown-then-rebuild reasoning as every other station's own reload in this codebase. */
    public void reload(Map<String, CrateDefinition> newContent) {
        for (CrateDefinition crate : crates) {
            EntityClickRegistry.unregister(crate.hitboxEntityId());
            Set<UUID> viewers = viewersByCrate.remove(crate);
            if (viewers == null) {
                continue;
            }
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) {
                    despawnFor(viewer, crate);
                }
            }
        }
        List<CrateDefinition> newCrates = List.copyOf(newContent.values());
        crates = newCrates;
        for (CrateDefinition crate : newCrates) {
            viewersByCrate.put(crate, ConcurrentHashMap.newKeySet());
            EntityClickRegistry.register(crate.hitboxEntityId(),
                    EntityClickRegistry.inReach(crate.location(), player -> handleClick(player, crate)));
        }
    }

    private void tick() {
        tickCount++;
        boolean refreshText = tickCount % TEXT_REFRESH_EVERY_N_TICKS == 0;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            org.bukkit.Location viewerAt = viewer.getLocation();
            for (CrateDefinition crate : crates) {
                Set<UUID> viewers = viewersByCrate.get(crate);
                if (viewers == null) {
                    continue;
                }
                boolean inRange = viewer.getWorld().equals(crate.location().getWorld())
                        && crate.location().distanceSquared(viewerAt) <= VIEW_DISTANCE_SQUARED;
                boolean seeing = viewers.contains(viewer.getUniqueId());
                if (inRange && !seeing) {
                    spawnFor(viewer, crate);
                    viewers.add(viewer.getUniqueId());
                } else if (!inRange && seeing) {
                    despawnFor(viewer, crate);
                    viewers.remove(viewer.getUniqueId());
                } else if (inRange && refreshText) {
                    TextDisplayManager.setText(viewer, crate.textEntityId(), buildText(viewer, crate));
                }
            }
        }
    }

    private void handleClick(Player player, CrateDefinition crate) {
        playPushAnimation(player, crate);
        CrateService.PurchaseOutcome outcome = crateService.open(player, crate.id());
        switch (outcome.result()) {
            case NOT_ENOUGH_KEYS -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                player.sendMessage(Text.parse("<red>You don't have a <crate> Key - find them by killing Ore Cubes!</red>",
                        Placeholder.unparsed("crate", crate.displayName())));
            }
            case BAG_FULL -> {
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                var storage = packs.getBagStorageService();
                var profile = packs.getPlayerStore().getOrCreate(player.getUniqueId());
                player.sendMessage(Text.parse("<red><msg></red>", Placeholder.unparsed("msg", storage.fullMessage(player, profile))));
            }
            case UNKNOWN_CRATE -> {
                // Shouldn't happen from a real click - the station this handler is bound to always matches a live crate.
            }
            case SUCCESS -> {
                CrateRewardEntry reward = outcome.reward();
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                player.sendMessage(Text.parse("<green>Crate opened! You got <reward>.</green>",
                        Placeholder.unparsed("reward", describeReward(reward))));
                TextDisplayManager.setText(player, crate.textEntityId(), buildText(player, crate));
            }
        }
    }

    private String describeReward(CrateRewardEntry reward) {
        return switch (reward.type()) {
            case FLAT_COINS -> "&6" + Formatting.format((double) reward.amount()) + " coins&r";
            case FLAT_DIAMONDS -> "&b" + Formatting.format((double) reward.amount()) + " diamonds&r";
            case PET -> "&da new pet&r";
            case COMMANDS -> "&da bonus&r";
        };
    }

    private void spawnFor(Player viewer, CrateDefinition crate) {
        Location loc = crate.location();

        PacketEntityManager.beginBundle(viewer);
        InteractionEntityManager.spawn(viewer, crate.hitboxEntityId(), hitboxLocation(loc));
        InteractionEntityManager.setSize(viewer, crate.hitboxEntityId(), HITBOX_SIZE, HITBOX_SIZE);

        BlockDisplayManager.spawn(viewer, crate.buttonEntityId(), loc);
        BlockDisplayManager.setBlockState(viewer, crate.buttonEntityId(), crate.icon());
        BlockDisplayManager.setTransformation(viewer, crate.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);

        // A plain stone pedestal under the crate - purely cosmetic, gives it
        // a base to visually sit on rather than floating over nothing.
        BlockDisplayManager.spawn(viewer, crate.wallEntityId(), loc.clone().add(0, -0.15, 0));
        BlockDisplayManager.setBlockState(viewer, crate.wallEntityId(), Material.SMOOTH_STONE_SLAB);

        TextDisplayManager.spawn(viewer, crate.textEntityId(), textLocation(loc));
        TextDisplayManager.setBillboard(viewer, crate.textEntityId(), TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, crate.textEntityId(), 0x00000000);
        TextDisplayManager.setStyle(viewer, crate.textEntityId(), true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setText(viewer, crate.textEntityId(), buildText(viewer, crate));
        PacketEntityManager.endBundle(viewer);
    }

    private Component buildText(Player viewer, CrateDefinition crate) {
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(viewer.getUniqueId());
        int keys = profile.getCrateKeys().getOrDefault(crate.id(), 0);
        return Text.parse("<name>\n&7Keys: &e<keys>\n&7Smack to open!",
                Placeholder.unparsed("name", Formatting.stripLeadingColorCodes(crate.displayName())),
                Placeholder.unparsed("keys", String.valueOf(keys)));
    }

    private void playPushAnimation(Player viewer, CrateDefinition crate) {
        BlockDisplayManager.setInterpolation(viewer, crate.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
        BlockDisplayManager.setTransformation(viewer, crate.buttonEntityId(), PUSH_TRANSLATE, PUSH_SCALE);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            BlockDisplayManager.setInterpolation(viewer, crate.buttonEntityId(), 0, PUSH_TICKS, PUSH_TICKS);
            BlockDisplayManager.setTransformation(viewer, crate.buttonEntityId(), BUTTON_TRANSLATE, BUTTON_SCALE);
        }, PUSH_TICKS);
    }

    private Location hitboxLocation(Location crateCorner) {
        return crateCorner.clone().add(0, HITBOX_INSET_Y, 0);
    }

    private Location textLocation(Location crateCorner) {
        return crateCorner.clone().add(0, 1.4, 0);
    }

    private void despawnFor(Player viewer, CrateDefinition crate) {
        PacketEntityManager.destroyEntity(viewer, crate.hitboxEntityId());
        PacketEntityManager.destroyEntity(viewer, crate.buttonEntityId());
        PacketEntityManager.destroyEntity(viewer, crate.wallEntityId());
        PacketEntityManager.destroyEntity(viewer, crate.textEntityId());
    }
}
