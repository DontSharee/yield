package me.dontshare.yieldzones.boss;

import me.dontshare.yieldcore.packet.PacketEntityManager;
import me.dontshare.yieldcore.packet.TextDisplayManager;
import me.dontshare.yieldcore.text.Formatting;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.pet.PetInstance;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldzones.cube.OreCubeService;
import me.dontshare.yieldzones.event.WorldBossKilledEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.RayTraceResult;
import me.dontshare.yieldzones.cube.LootDropService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Rare, GENUINELY SHARED encounters - the opposite of {@code OreCubeService}'s
 * per-player illusion. A boss is one real, everyone-visible giant {@link
 * BlockDisplay} entity with a real, solid {@code BARRIER} hitbox at its
 * center (vanilla's own block-interaction gives free, accurate click
 * detection for that - no packet raycasting needed, unlike ore cubes), one
 * shared HP pool every engaged player's pets chip away at together, and
 * rewards split proportionally by damage contributed once it dies.
 * <p>
 * Only one instance of a given boss id can be alive at a time. Engaging is
 * per-player (left-click a boss's barrier block to send your own equipped
 * pets at it, same left-click-to-target idiom as ore cubes) but the HP pool
 * and the visual/hitbox themselves belong to the boss, not to whoever
 * engaged it - anyone can start attacking an already-engaged boss.
 */
public final class WorldBossService implements Listener {

    private static final long TICK_INTERVAL = 4L;
    private static final long CHECK_INTERVAL_TICKS = 20L * 30; // how often the spawn-roll timer fires, independent of any one boss's own configured interval
    private static final long ATTACK_INTERVAL_TICKS = 20L;
    private static final long ARRIVAL_DELAY_TICKS = 1L;
    /** How far a player can be from a boss's center and still have their pets fight it - well beyond a zone's usual reach, since a boss is a big, dramatic, walk-up-to-it landmark rather than something you snipe from across the map. */
    private static final double ENGAGE_RANGE = 24.0;

    private final JavaPlugin plugin;
    private final YieldPacks packs;
    private final OreCubeService cubeService;
    private final Supplier<Map<String, WorldBossDefinition>> definitions;

    private final Map<String, WorldBoss> activeByBossId = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCheckMillisByBossId = new ConcurrentHashMap<>();
    private final Map<UUID, String> engagedBossIdByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Long>> cooldownsByPetByPlayer = new ConcurrentHashMap<>();
    private long currentTick;
    /** Who has each boss's top-of-screen bar up right now - everyone near it, engaged or not. */
    private final Map<String, java.util.Set<UUID>> barViewersByBossId = new ConcurrentHashMap<>();
    /** Within this of a boss you see its bar, whether or not your pets are on it. */
    private static final double BAR_VIEW_RANGE = 48.0;
    /** How far a swing reaches to engage a boss - a bit past normal reach, it's a big target. */
    private static final double SWING_REACH = 8.0;
    private static final int NAMETAG_BAR_SEGMENTS = 20;

    public WorldBossService(JavaPlugin plugin, YieldPacks packs, OreCubeService cubeService, Supplier<Map<String, WorldBossDefinition>> definitions) {
        this.plugin = plugin;
        this.packs = packs;
        this.cubeService = cubeService;
        this.definitions = definitions;
    }

    /** True while this player is currently sending pets at a boss instead of a cube - {@code PetCombatController} defers to this and skips its own tick for them entirely, so the two combat loops never fight over the same pets in the same tick. */
    public boolean isEngaged(Player player) {
        return engagedBossIdByPlayer.containsKey(player.getUniqueId());
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkSpawns, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkExpiry, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    private void checkSpawns() {
        long now = System.currentTimeMillis();
        for (WorldBossDefinition def : definitions.get().values()) {
            if (activeByBossId.containsKey(def.id())) {
                continue;
            }
            long last = lastCheckMillisByBossId.getOrDefault(def.id(), 0L);
            if (now - last < def.checkIntervalMillis()) {
                continue;
            }
            lastCheckMillisByBossId.put(def.id(), now);
            if (ThreadLocalRandom.current().nextDouble() < def.spawnChance()) {
                spawn(def);
            }
        }
    }

    private void checkExpiry() {
        long now = System.currentTimeMillis();
        for (WorldBoss boss : List.copyOf(activeByBossId.values())) {
            if (now - boss.spawnedAtMillis() >= boss.definition().despawnAfterMillis()) {
                despawnUnclaimed(boss);
            }
        }
    }

    /** Testing/admin hook - force-spawns regardless of the check timer, refusing only if that boss id is already alive. */
    public boolean forceSpawn(String bossId) {
        WorldBossDefinition def = definitions.get().get(bossId);
        if (def == null || activeByBossId.containsKey(bossId)) {
            return false;
        }
        spawn(def);
        return true;
    }

    private void spawn(WorldBossDefinition def) {
        World world = def.location().getWorld();
        Location center = def.location();
        int size = def.size();
        // Both the visual and the real hitbox are derived from this SAME
        // integer corner - deliberately not "center - size/2f" as a float
        // offset off the display entity's spawn point, which looked close
        // but was actually off by half a block in every axis: a block's
        // own world-space footprint is [n, n+1) starting at its integer
        // coordinate, not centered on it, so a barrier cube built from
        // center-radius..center+radius (ints) and a display transformed
        // symmetrically around the exact fractional center point cover two
        // different volumes even though both nominally have the same size.
        int radius = size / 2;
        int baseX = center.getBlockX() - radius;
        int baseY = center.getBlockY() - radius;
        int baseZ = center.getBlockZ() - radius;
        BlockDisplay display = spawnDisplay(def);

        List<Location> barrierLocations = new ArrayList<>();
        BlockData barrier = Material.BARRIER.createBlockData();
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                for (int dz = 0; dz < size; dz++) {
                    Location blockLoc = new Location(world, baseX + dx, baseY + dy, baseZ + dz);
                    blockLoc.getBlock().setBlockData(barrier, false);
                    barrierLocations.add(blockLoc);
                }
            }
        }

        WorldBoss boss = new WorldBoss(def, display, barrierLocations);
        boss.setNametag(spawnNametag(boss));
        activeByBossId.put(def.id(), boss);
        announce(world, "<#FF5555><bold>" + def.displayName() + " has appeared!</bold></#FF5555> <gray>Left-click it to send your pets.</gray>");
    }

    @EventHandler
    public void onLeftClickBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Location clicked = event.getClickedBlock().getLocation();
        for (WorldBoss boss : activeByBossId.values()) {
            if (containsBlock(boss, clicked)) {
                event.setCancelled(true);
                engage(event.getPlayer(), boss);
                return;
            }
        }
    }

    /**
     * Zone players are in Adventure mode, and an Adventure client never
     * tells the server it left-clicked a block - so {@link #onLeftClickBlock}
     * alone meant nobody could ever engage a boss, which is why its bar never
     * showed. The arm swing does always arrive; this traces from it to see
     * whether it was aimed at a boss, the same way cube clicks work.
     */
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING || activeByBossId.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        RayTraceResult hit = player.rayTraceBlocks(SWING_REACH);
        if (hit == null || hit.getHitBlock() == null) {
            return;
        }
        // Cubes are client-only, so the server's ray goes straight through
        // them: a boss standing behind the cube a player is hitting would
        // otherwise steal the swing. The nearer target wins.
        Double cubeDistance = cubeService.aimedCubeDistance(player, SWING_REACH);
        if (cubeDistance != null && cubeDistance < hit.getHitPosition().distance(player.getEyeLocation().toVector())) {
            return;
        }
        Location aimed = hit.getHitBlock().getLocation();
        for (WorldBoss boss : activeByBossId.values()) {
            if (containsBlock(boss, aimed)) {
                engage(player, boss);
                return;
            }
        }
    }

    /** Engagement and pet cooldowns go with the player; the top-bar viewer sets tidy themselves (see updateBarViewers). */
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        engagedBossIdByPlayer.remove(id);
        cooldownsByPetByPlayer.remove(id);
    }

    private boolean containsBlock(WorldBoss boss, Location clicked) {
        for (Location barrier : boss.barrierLocations()) {
            if (barrier.getWorld().equals(clicked.getWorld()) && barrier.getBlockX() == clicked.getBlockX()
                    && barrier.getBlockY() == clicked.getBlockY() && barrier.getBlockZ() == clicked.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    private void engage(Player player, WorldBoss boss) {
        UUID id = player.getUniqueId();
        boolean alreadyEngaged = boss.definition().id().equals(engagedBossIdByPlayer.get(id));
        engagedBossIdByPlayer.put(id, boss.definition().id());
        // Engaging a boss takes over this player's pets from the ore-cube
        // combat loop entirely (see isEngaged) - clearing their cube target
        // too keeps OreCubeService's own boss bar/state from lingering
        // stale, and means clicking a cube afterward is a clean re-target
        // rather than competing with a target that was never actually cleared.
        cubeService.clearTarget(player);
        showBossBarTo(player, boss);
        if (!alreadyEngaged) {
            cooldownsByPetByPlayer.remove(id);
            player.sendMessage(Text.parse("<red>You engage the " + boss.definition().displayName() + "!</red>"));
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1.4f);
        }
    }

    private void tick() {
        currentTick += TICK_INTERVAL;
        ensureDisplays();
        for (WorldBoss boss : activeByBossId.values()) {
            if (boss.dirty()) {
                boss.setDirty(false);
                updateBossBar(boss);
                updateNametag(boss);
            }
            // Who's near enough to see the bar only needs checking once a
            // second, not every combat tick.
            if (currentTick % 20 < TICK_INTERVAL) {
                updateBarViewers(boss);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            tickPlayer(player);
        }
    }

    private void tickPlayer(Player player) {
        UUID id = player.getUniqueId();
        String bossId = engagedBossIdByPlayer.get(id);
        if (bossId == null) {
            return;
        }
        WorldBoss boss = activeByBossId.get(bossId);
        if (boss == null) {
            disengage(player);
            return;
        }
        if (player.getWorld() != boss.center().getWorld() || player.getLocation().distanceSquared(boss.center()) > ENGAGE_RANGE * ENGAGE_RANGE) {
            disengage(player);
            return;
        }

        PackPlayerProfile profile = packs.getPlayerStore().getCached(id);
        if (profile == null) {
            return;
        }
        List<UUID> equipped = profile.getEquippedPetIds();
        if (equipped.isEmpty()) {
            packs.getPetDisplayService().clearAttackTarget(player);
            return;
        }

        Location centered = boss.center().clone().add(0.5, 0.5, 0.5);
        Map<Integer, Location> slotTargets = new java.util.HashMap<>();
        for (int slot = 0; slot < equipped.size(); slot++) {
            slotTargets.put(slot, centered);
        }
        // The boss is size x size blocks - ring the pets around its outside,
        // on its floor, rather than a normal cube's radius inside it.
        packs.getPetDisplayService().setAttackTargets(player, slotTargets,
                Map.of(centered, me.dontshare.yieldpacks.display.PetDisplayService.ringRadiusFor(boss.definition().size())));

        Map<UUID, Long> cooldowns = cooldownsByPetByPlayer.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        for (int slot = 0; slot < equipped.size(); slot++) {
            UUID petId = equipped.get(slot);
            if (currentTick < cooldowns.getOrDefault(petId, 0L)) {
                continue;
            }
            PetInstance pet = profile.findPet(petId).orElse(null);
            if (pet == null) {
                continue;
            }
            cooldowns.put(petId, currentTick + Math.round(ATTACK_INTERVAL_TICKS / packs.attackSpeedMultiplierCached(profile)));
            double damage = packs.getEquipmentService().effectiveDamage(profile, pet) * packs.damageMultiplierCached(profile);
            queueDamage(player, boss, Math.round(damage), petId);
            packs.getPetDisplayService().playAttackLunge(player, slot);
        }
    }

    /** The boss's model, at the integer corner its barrier hitbox is built from (see {@link #spawn}). */
    private BlockDisplay spawnDisplay(WorldBossDefinition def) {
        World world = def.location().getWorld();
        int size = def.size();
        int radius = size / 2;
        Location corner = new Location(world, def.location().getBlockX() - radius,
                def.location().getBlockY() - radius, def.location().getBlockZ() - radius);
        return world.spawn(corner, BlockDisplay.class, entity -> {
            entity.setBlock(def.material().createBlockData());
            entity.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(size, size, size),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
            // Not saved with the chunk - a restart must never leave a model
            // behind for a boss the server no longer knows about. The flip
            // side is that the chunk unloading discards it too, which is
            // what ensureDisplays puts right.
            entity.setPersistent(false);
        });
    }

    /**
     * A non-persistent display is thrown away when its chunk unloads, but
     * the boss itself (HP, bar, barrier hitbox) lives on - so without this
     * a boss whose area emptied out came back invisible, an unclickable-
     * looking wall of barriers. Respawns the model once the chunk is back.
     */
    private void ensureDisplays() {
        for (WorldBoss boss : activeByBossId.values()) {
            BlockDisplay display = boss.displayEntity();
            TextDisplay nametag = boss.nametag();
            boolean modelOk = display != null && display.isValid();
            boolean tagOk = nametag != null && nametag.isValid();
            if (modelOk && tagOk) {
                continue;
            }
            Location at = boss.definition().location();
            if (at.getWorld() == null || !at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                continue;
            }
            if (!modelOk) {
                boss.setDisplayEntity(spawnDisplay(boss.definition()));
            }
            if (!tagOk) {
                boss.setNametag(spawnNametag(boss));
            }
        }
    }

    /**
     * Barrier blocks are real world blocks and are saved with it; the boss
     * that placed them is not. After a restart or crash with a boss up, its
     * hitbox stayed in the world forever. Clears every BARRIER inside the
     * footprint of any configured boss that isn't alive right now - called
     * whenever worldboss.yml is (re)loaded, which includes startup.
     */
    public void clearLeftoverBarriers() {
        for (WorldBossDefinition def : definitions.get().values()) {
            if (activeByBossId.containsKey(def.id()) || def.location().getWorld() == null) {
                continue;
            }
            World world = def.location().getWorld();
            int size = def.size();
            int radius = size / 2;
            int baseX = def.location().getBlockX() - radius;
            int baseY = def.location().getBlockY() - radius;
            int baseZ = def.location().getBlockZ() - radius;
            for (int dx = 0; dx < size; dx++) {
                for (int dy = 0; dy < size; dy++) {
                    for (int dz = 0; dz < size; dz++) {
                        var block = world.getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                        if (block.getType() == Material.BARRIER) {
                            block.setType(Material.AIR, false);
                        }
                    }
                }
            }
        }
    }

    /** Plugin shutdown: every live boss's model and barriers go with it, so nothing is left in the world for the next start to trip over. */
    public void shutdown() {
        for (WorldBoss boss : List.copyOf(activeByBossId.values())) {
            activeByBossId.remove(boss.definition().id());
            if (boss.displayEntity() != null) {
                boss.displayEntity().remove();
            }
            removeNametagAndBar(boss);
            for (Location barrier : boss.barrierLocations()) {
                barrier.getBlock().setType(Material.AIR, false);
            }
            BossBar bar = boss.bossBar();
            if (bar != null) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.hideBossBar(bar);
                }
            }
        }
        engagedBossIdByPlayer.clear();
    }

    /** Also called from {@code YieldZones}' cube click handler - clicking a regular cube while boss-engaged switches your pets back over, rather than staying locked onto the boss with no way out short of walking {@link #ENGAGE_RANGE} away. */
    public void disengage(Player player) {
        // A no-op unless a boss really has this player's pets: every cube
        // click comes through here, and clearing the pets' attack target on
        // each one snapped the squad back to the player until the next
        // combat tick sent it out again - pets bouncing back and forth
        // under a spam-click.
        if (engagedBossIdByPlayer.remove(player.getUniqueId()) == null) {
            return;
        }
        cooldownsByPetByPlayer.remove(player.getUniqueId());
        packs.getPetDisplayService().clearAttackTarget(player);
    }

    private void queueDamage(Player player, WorldBoss boss, long amount, UUID petInstanceId) {
        if (amount <= 0 || boss.isDead() || !activeByBossId.containsValue(boss)) {
            return;
        }
        boss.damage(amount);
        boss.addContribution(player.getUniqueId(), amount);
        // Redrawn at most once per tick (see tick) - every pet of every
        // player hitting it would otherwise rebuild the bar per hit.
        boss.setDirty(true);
        showDamageIndicator(player, boss, amount);

        PackPlayerProfile profile = packs.getPlayerStore().getCached(player.getUniqueId());
        if (profile != null) {
            profile.setLifetimeBossDamage(profile.getLifetimeBossDamage() + amount);
            // Not saved on every single hit (attacks land every ~1s per pet -
            // that would be a save storm) - PlayerDataStore's own autosave
            // loop and the normal save-on-other-actions already flush this
            // periodically, same as every other lifetime counter.
        }
        if (boss.isDead() && activeByBossId.remove(boss.definition().id(), boss)) {
            killBoss(boss);
        }
    }

    private void showDamageIndicator(Player viewer, WorldBoss boss, long amount) {
        // Bigger and bolder than a cube's chip damage - every hit on a boss
        // is a sliver of a huge bar, so the number carries the weight.
        Component text = Text.parse("<bold><#FF6A2B>-<amount></#FF6A2B></bold>", Placeholder.unparsed("amount", Formatting.format(amount)));
        Location center = boss.center().clone().add(
                ThreadLocalRandom.current().nextDouble(-1, 1), boss.definition().size() + 0.5, ThreadLocalRandom.current().nextDouble(-1, 1));

        int entityId = PacketEntityManager.nextEntityId();
        PacketEntityManager.beginBundle(viewer);
        TextDisplayManager.spawn(viewer, entityId, center);
        TextDisplayManager.setBillboard(viewer, entityId, TextDisplayManager.Billboard.VERTICAL);
        TextDisplayManager.setBackgroundColor(viewer, entityId, 0x00000000);
        TextDisplayManager.setStyle(viewer, entityId, true, false, false, TextDisplayManager.Alignment.CENTER);
        TextDisplayManager.setScale(viewer, entityId, 1.3f, 1.3f, 1.3f);
        TextDisplayManager.setText(viewer, entityId, text);
        TextDisplayManager.setInterpolation(viewer, entityId, 0, 10, 10);
        PacketEntityManager.endBundle(viewer);

        PacketEntityManager.teleportEntity(viewer, entityId, center.clone().add(0, 0.9, 0));
        Bukkit.getScheduler().runTaskLater(plugin, () -> PacketEntityManager.destroyEntity(viewer, entityId), 12L);
    }

    private void killBoss(WorldBoss boss) {
        WorldBossDefinition def = boss.definition();
        World world = def.location().getWorld();

        if (boss.displayEntity() != null) {
            boss.displayEntity().remove();
        }
        removeNametagAndBar(boss);
        for (Location barrier : boss.barrierLocations()) {
            barrier.getBlock().setType(Material.AIR, false);
        }
        for (Player player : world.getPlayers()) {
            BossBar bar = boss.bossBar();
            if (bar != null) {
                player.hideBossBar(bar);
            }
        }
        for (Map.Entry<UUID, String> entry : List.copyOf(engagedBossIdByPlayer.entrySet())) {
            if (def.id().equals(entry.getValue())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    disengage(player);
                }
            }
        }

        long totalDamage = boss.totalDamageDealt();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, def.location(), 1);
        world.playSound(def.location(), Sound.ENTITY_WITHER_DEATH, 1f, 0.8f);
        announce(world, "<#55FF7F><bold>" + def.displayName() + " has been defeated!</bold></#55FF7F>");

        for (Map.Entry<UUID, Long> entry : boss.damageByPlayer().entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || totalDamage <= 0) {
                continue;
            }
            double share = entry.getValue() / (double) totalDamage;
            long coins = Math.round(def.rewardCoins() * share);
            long diamonds = Math.round(def.rewardDiamonds() * share);
            payOut(player, boss, coins, diamonds);
        }

        Bukkit.getPluginManager().callEvent(new WorldBossKilledEvent(def, Map.copyOf(boss.damageByPlayer()), def.rewardCoins(), def.rewardDiamonds()));
    }

    private void despawnUnclaimed(WorldBoss boss) {
        if (!activeByBossId.remove(boss.definition().id(), boss)) {
            return;
        }
        if (boss.displayEntity() != null) {
            boss.displayEntity().remove();
        }
        removeNametagAndBar(boss);
        for (Location barrier : boss.barrierLocations()) {
            barrier.getBlock().setType(Material.AIR, false);
        }
        World world = boss.definition().location().getWorld();
        for (Player player : world.getPlayers()) {
            BossBar bar = boss.bossBar();
            if (bar != null) {
                player.hideBossBar(bar);
            }
        }
        for (Map.Entry<UUID, String> entry : List.copyOf(engagedBossIdByPlayer.entrySet())) {
            if (boss.definition().id().equals(entry.getValue())) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) {
                    disengage(player);
                }
            }
        }
        announce(world, "<gray>" + boss.definition().displayName() + " has retreated, unclaimed.</gray>");
    }

    /**
     * Each contributor's share sprays out of the boss as a fountain of coins
     * and diamonds - far more pieces than any cube - and flies to them the
     * same way cube loot does (see LootDropService), credited as it lands.
     */
    private void payOut(Player player, WorldBoss boss, long coins, long diamonds) {
        LootDropService drops = cubeService.getLootDrops();
        Location center = boss.visualCenter();
        double spread = boss.definition().size() / 2.0;
        drops.spawn(player, center, boss.floorY(), spread, LootDropService.Kind.COIN, coins, 36);
        drops.spawn(player, center, boss.floorY(), spread, LootDropService.Kind.DIAMOND, diamonds, 14);
        player.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        player.sendMessage(Text.parse("<green>You earned <gold>" + Formatting.format(coins) + " coins</gold>"
                + (diamonds > 0 ? " <gray>and</gray> <aqua>" + Formatting.format(diamonds) + " diamonds</aqua>" : "") + "!</green>"));
    }

    private void updateBossBar(WorldBoss boss) {
        float progress = Math.max(0f, Math.min(1f, boss.hp() / (float) boss.definition().maxHp()));
        BossBar bar = boss.bossBar();
        Component title = Text.parse(boss.definition().displayName() + " <#FF5555>" + Formatting.format(boss.hp())
                + "</#FF5555>/" + Formatting.format(boss.definition().maxHp()));
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
            boss.setBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }
    }

    private void showBossBarTo(Player player, WorldBoss boss) {
        if (boss.bossBar() == null) {
            updateBossBar(boss);
        }
        player.showBossBar(boss.bossBar());
    }

    /**
     * The name above the boss with its health bar underneath and the HP
     * below that - a real entity, so every player sees the same one.
     * Not persistent, like the model: {@link #ensureDisplays} brings it back
     * if its chunk unloads.
     */
    private TextDisplay spawnNametag(WorldBoss boss) {
        Location at = boss.visualCenter().add(0, boss.definition().size() / 2.0 + 0.9, 0);
        return at.getWorld().spawn(at, TextDisplay.class, tag -> {
            tag.setBillboard(Display.Billboard.CENTER);
            tag.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            tag.setShadowed(true);
            tag.setAlignment(TextDisplay.TextAlignment.CENTER);
            float scale = 1.3f + boss.definition().size() * 0.1f;
            tag.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            tag.text(nametagText(boss));
            tag.setPersistent(false);
        });
    }

    private void updateNametag(WorldBoss boss) {
        TextDisplay tag = boss.nametag();
        if (tag != null && tag.isValid()) {
            tag.text(nametagText(boss));
        }
    }

    /** Name on top, the bar under it, then "❤ hp / max". */
    private Component nametagText(WorldBoss boss) {
        long max = Math.max(1L, boss.definition().maxHp());
        int filled = (int) Math.ceil(NAMETAG_BAR_SEGMENTS * (boss.hp() / (double) max));
        filled = Math.max(boss.hp() > 0 ? 1 : 0, Math.min(NAMETAG_BAR_SEGMENTS, filled));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < NAMETAG_BAR_SEGMENTS; i++) {
            bar.append(i < filled ? "<#FF3B3B><st> </st></#FF3B3B>" : "<#3A3A3A><st> </st></#3A3A3A>");
        }
        return Text.parse("<#FF5555><bold>" + boss.definition().displayName() + "</bold></#FF5555>\n"
                + bar + "\n<dark_red>❤</dark_red> <red>" + Formatting.format(boss.hp()) + "</red><gray> / "
                + Formatting.format(max) + "</gray>" + topDamagers(boss));
    }

    private static final String[] PLACE_COLORS = {"#FFD700", "#D0D0D0", "#CD7F32"};

    /**
     * The three biggest hitters so far, under the HP line - "#1 Name 12.5K
     * (43%)". Only rebuilt when the boss redraws (at most once a tick), and
     * only online contributors are named, so no name lookup ever touches
     * disk.
     */
    private String topDamagers(WorldBoss boss) {
        long total = boss.totalDamageDealt();
        if (total <= 0) {
            return "";
        }
        List<Map.Entry<UUID, Long>> top = boss.damageByPlayer().entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .limit(3)
                .toList();
        StringBuilder lines = new StringBuilder("\n");
        for (int i = 0; i < top.size(); i++) {
            Player player = Bukkit.getPlayer(top.get(i).getKey());
            String name = player != null ? player.getName() : "Someone";
            long dealt = top.get(i).getValue();
            lines.append("\n<").append(PLACE_COLORS[i]).append("><bold>#").append(i + 1).append("</bold></")
                    .append(PLACE_COLORS[i]).append("> <white>").append(name).append("</white> <red>")
                    .append(Formatting.format(dealt)).append("</red> <gray>(")
                    .append(Math.round(dealt * 100.0 / total)).append("%)</gray>");
        }
        return lines.toString();
    }

    /** Everyone within {@link #BAR_VIEW_RANGE} sees the boss bar; walking off takes it away. */
    private void updateBarViewers(WorldBoss boss) {
        if (boss.bossBar() == null) {
            updateBossBar(boss);
        }
        java.util.Set<UUID> viewers = barViewersByBossId.computeIfAbsent(boss.definition().id(), id -> ConcurrentHashMap.newKeySet());
        Location center = boss.visualCenter();
        for (Player player : center.getWorld().getPlayers()) {
            boolean near = player.getLocation().distanceSquared(center) <= BAR_VIEW_RANGE * BAR_VIEW_RANGE;
            boolean showing = viewers.contains(player.getUniqueId());
            if (near && !showing) {
                player.showBossBar(boss.bossBar());
                viewers.add(player.getUniqueId());
            } else if (!near && showing) {
                player.hideBossBar(boss.bossBar());
                viewers.remove(player.getUniqueId());
            }
        }
        // Left the boss's world, or the server: they're no longer in the
        // loop above, so take the bar away here.
        viewers.removeIf(id -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                return true;
            }
            if (!player.getWorld().equals(center.getWorld())) {
                player.hideBossBar(boss.bossBar());
                return true;
            }
            return false;
        });
    }

    private void removeNametagAndBar(WorldBoss boss) {
        if (boss.nametag() != null) {
            boss.nametag().remove();
        }
        barViewersByBossId.remove(boss.definition().id());
    }

    private void announce(World world, String message) {
        Component component = Text.parse(message);
        for (Player player : world.getPlayers()) {
            player.sendMessage(component);
        }
    }
}
