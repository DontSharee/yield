package me.dontshare.yieldzones;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import me.dontshare.yieldcore.YieldCore;
import me.dontshare.yieldcore.command.CommandManager;
import me.dontshare.yieldcore.text.Text;
import me.dontshare.yieldpacks.YieldPacks;
import me.dontshare.yieldpacks.mastery.MasteryStat;
import me.dontshare.yieldpacks.mastery.MasteryType;
import me.dontshare.yieldpacks.player.AttackMode;
import me.dontshare.yieldpacks.player.PackPlayerProfile;
import me.dontshare.yieldpacks.player.SendMode;
import me.dontshare.yieldzones.boss.WorldBossContentLoader;
import me.dontshare.yieldzones.boss.WorldBossDefinition;
import me.dontshare.yieldzones.boss.WorldBossService;
import me.dontshare.yieldzones.command.FastTravelCommand;
import me.dontshare.yieldzones.cube.OreCubeService;
import me.dontshare.yieldzones.cube.PetCombatController;
import me.dontshare.yieldzones.cube.SneakRecallListener;
import me.dontshare.yieldzones.data.ZoneContentLoader;
import me.dontshare.yieldzones.data.ZoneDefinition;
import me.dontshare.yieldzones.data.ZoneRegion;
import me.dontshare.yieldzones.gui.FastTravelGui;
import me.dontshare.yieldzones.gui.ZonePurchaseGui;
import me.dontshare.yieldzones.zone.ZoneLockService;
import me.dontshare.yieldzones.data.CubeTier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.math.BigInteger;

public final class YieldZones extends JavaPlugin {

    private ZoneContentLoader contentLoader;
    private volatile Map<String, ZoneDefinition> zones;
    private OreCubeService cubeService;
    private PetCombatController combatController;
    private ZoneLockService lockService;
    private WorldBossContentLoader worldBossContentLoader;
    private volatile Map<String, WorldBossDefinition> worldBosses;
    private WorldBossService worldBossService;

    @Override
    public void onEnable() {
        contentLoader = new ZoneContentLoader(this, getLogger());
        worldBossContentLoader = new WorldBossContentLoader(this, getLogger());
        // Safe empty defaults until the deferred load below actually runs -
        // every service constructed in this method reads zones/worldBosses
        // through a "() -> zones"-style supplier, never a snapshot, so they
        // pick up the real data automatically once it lands.
        zones = Map.of();
        worldBosses = Map.of();

        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        cubeService = new OreCubeService(this, () -> zones, packs);
        cubeService.start();

        worldBossService = new WorldBossService(this, packs, cubeService, () -> worldBosses);
        worldBossService.start();
        Bukkit.getPluginManager().registerEvents(worldBossService, this);

        combatController = new PetCombatController(this, packs, cubeService, worldBossService);
        combatController.start();
        Bukkit.getPluginManager().registerEvents(combatController, this);
        // Self-registered here (not by yield-packs itself) since Shards are
        // a yield-packs concept but crit chance is entirely a yield-zones
        // one - same direction every other cross-plugin provider
        // registration in this codebase already goes.
        combatController.registerCritChanceProvider("shards", profile -> packs.getShardService().critChanceBonus(profile));
        // Same "self-registered here, not by yield-packs" reasoning as
        // Shards' own crit registration above - Mastery perks are a
        // yield-packs concept, but crit/multi-hit/cube-cap/diamond-chance
        // are entirely yield-zones ones.
        combatController.registerCritChanceProvider("mastery_combat",
                profile -> packs.getMasteryService().sumStat(profile, MasteryType.COMBAT, MasteryStat.CRIT_CHANCE));
        combatController.registerDoubleHitChanceProvider("mastery_combat",
                profile -> packs.getMasteryService().sumStat(profile, MasteryType.COMBAT, MasteryStat.DOUBLE_HIT_CHANCE));
        combatController.registerTripleHitChanceProvider("mastery_combat",
                profile -> packs.getMasteryService().sumStat(profile, MasteryType.COMBAT, MasteryStat.TRIPLE_HIT_CHANCE));
        cubeService.registerExtraCubeCapProvider("mastery_mining",
                profile -> (int) Math.round(packs.getMasteryService().sumStat(profile, MasteryType.MINING, MasteryStat.EXTRA_CUBE_CAP)));
        cubeService.registerDiamondChanceBoostProvider("mastery_mining",
                profile -> packs.getMasteryService().sumStat(profile, MasteryType.MINING, MasteryStat.DIAMOND_CHANCE_BOOST));
        cubeService.registerCubeBonusChanceBoostProvider("mastery_mining",
                profile -> packs.getMasteryService().sumStat(profile, MasteryType.MINING, MasteryStat.CUBE_BONUS_CHANCE_BOOST));

        // Left-click behavior depends on the player's current send mode -
        // AUTO always overrides the shared target (there's no per-slot
        // "single" concept under Auto anymore - see AutoTargetMode); MANUAL
        // (the default) branches on AttackMode instead (SINGLE = next-pet
        // rotation, ALL = shared target). Clicking a regular cube always
        // disengages any world boss first - otherwise a player who'd ever
        // engaged one would stay locked onto it with no way to switch back,
        // since WorldBossService's own tick loop keeps claiming their pets
        // every tick regardless of what they click here.
        cubeService.setClickHandler((clicker, cube) -> {
            worldBossService.disengage(clicker);
            PackPlayerProfile profile = packs.getPlayerStore().getCached(clicker.getUniqueId());
            boolean sendOneAtATime = profile != null && profile.getSendMode() == SendMode.MANUAL
                    && profile.getAttackMode() == AttackMode.SINGLE;
            if (sendOneAtATime) {
                combatController.assignNextPetTo(clicker, cube);
            } else {
                combatController.assignSharedTarget(clicker, cube);
            }
        });

        Bukkit.getPluginManager().registerEvents(new SneakRecallListener(combatController), this);

        YieldCore core = JavaPlugin.getPlugin(YieldCore.class);
        lockService = new ZoneLockService(this, packs, () -> zones);
        lockService.start();
        Bukkit.getPluginManager().registerEvents(lockService, this);
        ZonePurchaseGui purchaseGui = new ZonePurchaseGui(core.getGuiManager(), lockService);
        lockService.setPurchaseGui(purchaseGui);
        FastTravelGui fastTravelGui = new FastTravelGui(core.getGuiManager(), () -> zones, lockService, purchaseGui);
        CommandManager.register(this, FastTravelCommand.build(fastTravelGui), "Teleport to any zone you've unlocked", List.of("warp"));
        // The Enchant Market prices books in "basic cubes" of the player's
        // richest zone - only this plugin knows what a zone's basic cube is
        // worth, so it answers for yield-packs.
        packs.getEnchantMarketService().setPriceBasis(player -> enchantPriceBasis(packs, player));

        core.getAdminCommandRegistry().register(buildAdminCommand());

        // zones.yml/worldboss.yml can reference worlds created by OTHER
        // plugins (Multiverse-Core, in this server's case) - plugin enable
        // order isn't guaranteed to put those world-creating plugins first,
        // so loading content here, synchronously, could run before a
        // referenced world exists yet and silently fall back to the
        // default world (exactly what a manual /admin zones reload after
        // full startup was "fixing" by accident, every single restart).
        // Deferring the real load to the very next tick guarantees every
        // other plugin's onEnable - and any world it creates - has already
        // finished, since Bukkit/Paper don't start ticking until the whole
        // server has finished enabling every plugin.
        Bukkit.getScheduler().runTask(this, this::reloadContent);
    }

    /** Re-reads zones.yml and worldboss.yml - existing live cubes/targets/bosses are unaffected until players naturally cross zone boundaries again (or a boss dies/expires). */
    public void reloadContent() {
        zones = contentLoader.load();
        worldBosses = worldBossContentLoader.load(zones);
    }

    /** Exposed so other plugins (e.g. the admin unlock command) can reach the live cube service directly. Zone-transition/unlock reactions go through {@code ZoneEnteredEvent}/{@code ZoneUnlockedEvent} instead - a real Bukkit event, not this getter - so any number of plugins can listen without yield-zones needing any awareness of them. */
    public OreCubeService getCubeService() {
        return cubeService;
    }

    /** Exposed for the same reason as {@link #getCubeService()} - lets e.g. yield-blocktree register its own extra-hit-chance providers. */
    public PetCombatController getPetCombatController() {
        return combatController;
    }

    /** Exposed for the same reason as {@link #getCubeService()} - lets e.g. yield-upgrades check whether a player has a given zone unlocked before letting them use one of its physical stations. */
    public ZoneLockService getZoneLockService() {
        return lockService;
    }

    /** The live zone registry - always current after {@link #reloadContent}. Read through this method (or capture a {@code () -> zones}-style supplier) rather than caching the returned map, since a reload replaces it wholesale rather than mutating it in place. */
    public Map<String, ZoneDefinition> getZones() {
        return zones;
    }

    private LiteralCommandNode<CommandSourceStack> buildAdminCommand() {
        return Commands.literal("zones")
                .then(Commands.literal("reload")
                        .executes(ctx -> {
                            reloadContent();
                            ctx.getSource().getSender().sendMessage(Text.parse("<green>yield-zones content reloaded (" + zones.size() + " zone(s)).</green>"));
                            return Command.SINGLE_SUCCESS;
                        }))
                .then(Commands.literal("unlock")
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .then(Commands.argument("zone", StringArgumentType.word())
                                        .executes(this::adminSetUnlock))))
                .then(Commands.literal("spawnboss")
                        .then(Commands.argument("boss", StringArgumentType.word())
                                .executes(this::adminSpawnBoss)))
                .then(Commands.literal("buildplatform")
                        .then(Commands.argument("zone", StringArgumentType.word())
                                .executes(this::adminBuildPlatform)))
                .build();
    }

    /**
     * A new zone's region is just a marked-off volume of air - nothing
     * guarantees real ground exists underneath it (a fresh zone dropped
     * into an empty void-generated world definitely won't have any).
     * This lays down a simple flat platform one block below the zone's
     * own floor (its region's minY), themed per zone id, so a
     * newly-added zone is actually walkable/has somewhere for cubes to
     * land the moment it's configured - no WorldEdit/schematic needed for
     * something this simple. Safe to re-run on an already-built zone.
     */
    private int adminBuildPlatform(CommandContext<CommandSourceStack> ctx) {
        String zoneId = StringArgumentType.getString(ctx, "zone");
        ZoneDefinition zone = zones.get(zoneId);
        if (zone == null) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>No such zone '" + zoneId + "'.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        ZoneRegion region = zone.region();
        World world = region.world();
        Material top = platformTopMaterial(zoneId);
        Material border = platformBorderMaterial(zoneId);
        int y = region.minY() - 1;
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                boolean edge = x == region.minX() || x == region.maxX() || z == region.minZ() || z == region.maxZ();
                world.getBlockAt(x, y, z).setType(edge ? border : top, false);
            }
        }
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Built a platform for '" + zoneId + "' at y=" + y + ".</green>"));
        return Command.SINGLE_SUCCESS;
    }

    private Material platformTopMaterial(String zoneId) {
        return switch (zoneId) {
            case "scorched_wastes" -> Material.NETHERRACK;
            case "crystal_caverns" -> Material.CALCITE;
            case "void_expanse" -> Material.BLACKSTONE;
            case "celestial_sanctum" -> Material.QUARTZ_BLOCK;
            default -> Material.GRASS_BLOCK;
        };
    }

    private Material platformBorderMaterial(String zoneId) {
        return switch (zoneId) {
            case "scorched_wastes" -> Material.POLISHED_BLACKSTONE_BRICKS;
            case "crystal_caverns" -> Material.AMETHYST_BLOCK;
            case "void_expanse" -> Material.OBSIDIAN;
            case "celestial_sanctum" -> Material.GOLD_BLOCK;
            default -> Material.RED_TERRACOTTA;
        };
    }

    /** Testing/support shortcut - force-spawns a world boss immediately, bypassing its own check-interval/spawn-chance roll. Refuses if that boss id is already alive. */
    private int adminSpawnBoss(CommandContext<CommandSourceStack> ctx) {
        String bossId = StringArgumentType.getString(ctx, "boss");
        if (!worldBosses.containsKey(bossId)) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>No such world boss '" + bossId + "'.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        if (worldBossService.forceSpawn(bossId)) {
            ctx.getSource().getSender().sendMessage(Text.parse("<green>Spawned '" + bossId + "'.</green>"));
        } else {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>'" + bossId + "' is already alive.</red>"));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Testing/support shortcut - grants a zone's unlock directly, bypassing its actual cost. */
    private int adminSetUnlock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        PlayerSelectorArgumentResolver resolver = ctx.getArgument("target", PlayerSelectorArgumentResolver.class);
        Player target = resolver.resolve(ctx.getSource()).getFirst();
        String zoneId = StringArgumentType.getString(ctx, "zone");
        if (!zones.containsKey(zoneId)) {
            ctx.getSource().getSender().sendMessage(Text.parse("<red>No such zone '" + zoneId + "'.</red>"));
            return Command.SINGLE_SUCCESS;
        }
        YieldPacks packs = JavaPlugin.getPlugin(YieldPacks.class);
        PackPlayerProfile profile = packs.getPlayerStore().getOrCreate(target.getUniqueId());
        profile.getUnlockedZoneIds().add(zoneId);
        packs.getPlayerStore().save(target.getUniqueId());
        ctx.getSource().getSender().sendMessage(Text.parse("<green>Unlocked '" + zoneId + "' for " + target.getName() + ".</green>"));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The coin value of one basic cube - the zone's most common ordinary
     * tier - in the richest LADDER zone this player has unlocked.
     * <p>
     * Ladder means the zone has its own egg ({@code zone_<id>_pack}), the
     * same rule the pacing model uses to tell the progression from
     * everything else. Without it the free, always-"unlocked" Haunted
     * Hollow - whose basic cube pays 150 against the Meadow's 10 - would set
     * a brand-new player's prices fifteen times too high.
     */
    private BigInteger enchantPriceBasis(YieldPacks packs, Player player) {
        long best = 0;
        for (ZoneDefinition zone : zones.values()) {
            if (packs.getPackRegistry().find("zone_" + zone.id() + "_pack").isEmpty()
                    || !lockService.isUnlocked(player, zone)) {
                continue;
            }
            CubeTier basic = null;
            for (CubeTier tier : zone.cubeTiers()) {
                if (!tier.treasure() && !tier.giant() && (basic == null || tier.weight() > basic.weight())) {
                    basic = tier;
                }
            }
            if (basic != null) {
                best = Math.max(best, basic.coinValue());
            }
        }
        return BigInteger.valueOf(Math.max(1, best));
    }
}
