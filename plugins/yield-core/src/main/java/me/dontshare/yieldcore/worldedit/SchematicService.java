package me.dontshare.yieldcore.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Thin wrapper around FastAsyncWorldEdit's clipboard/paste API - loading
 * {@code .schem} files from {@code plugins/yield/schematics/} (or a
 * subfolder of it - see {@link #listSchematics}, used to pick randomly
 * from a pool of interchangeable options rather than one fixed file) and
 * pasting them into a world, optionally rotated. Everything here runs off
 * the main thread on a small dedicated pool (FAWE is built for exactly
 * this - fast async edits - so a paste never blocks the server the way a
 * naive block-by-block loop on the main thread would).
 * <p>
 * Deliberately doesn't offer a way to read {@code OBSERVER} anchor blocks
 * directly out of a loaded {@link Clipboard} - reading them out of a
 * clipboard's own internals has proven unreliable (wrong offsets, stale
 * data left over from a previously loaded, differently-sized schematic).
 * The one thing that reliably works is scanning real, already-placed
 * Bukkit blocks - see {@link #findAnchorsInWorld} - so that's the only
 * anchor-finding mechanism this class offers: paste first, then scan what's
 * actually in the world.
 * <p>
 * Deliberately knows nothing about any higher-level grid/placement concept
 * this project builds on top of - this is purely the low-level "load a
 * schematic, paste it somewhere, rotated, find/clear real blocks in a
 * region" primitive. Only constructed if the FastAsyncWorldEdit plugin is
 * actually installed - see {@code YieldCore}.
 */
public final class SchematicService {

    private final File schematicsFolder;
    private final Logger logger;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    // Schematic files are static once loaded - each paste wraps the same
    // cached Clipboard in its own ClipboardHolder rather than mutating it,
    // so one read per distinct schematic for the life of the server is fine.
    private final Map<String, Clipboard> cache = new ConcurrentHashMap<>();

    public SchematicService(JavaPlugin plugin) {
        this.schematicsFolder = new File(plugin.getDataFolder(), "schematics");
        this.logger = plugin.getLogger();
        if (!schematicsFolder.exists()) {
            schematicsFolder.mkdirs();
        }
    }

    /** Loads (or returns the already-cached) clipboard for {@code name}, with or without a ".schem" extension. */
    public CompletableFuture<Clipboard> load(String name) {
        Clipboard cached = cache.get(name);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            File file = resolveFile(name);
            if (file == null) {
                throw new IllegalArgumentException("No schematic named \"" + name + "\" in " + schematicsFolder);
            }

            ClipboardFormat format = ClipboardFormats.findByFile(file);
            if (format == null) {
                throw new IllegalArgumentException("Unrecognized schematic format: " + file.getName());
            }

            try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
                Clipboard clipboard = reader.read();
                cache.put(name, clipboard);
                return clipboard;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read schematic " + file.getName(), e);
            }
        }, executor);
    }

    /**
     * Pastes an already-loaded clipboard at {@code location}, rotated so
     * its saved orientation (treated as "facing north", 0 degrees) instead
     * faces {@code facing} - only NORTH/EAST/SOUTH/WEST are meaningful,
     * anything else pastes unrotated. Rotation direction (clockwise vs.
     * counter-clockwise) is WorldEdit's own {@link AffineTransform#rotateY}
     * convention and hasn't been independently verified here - check it
     * visually in-game the first time a rotated paste is actually used.
     * <p>
     * Air blocks in the schematic overwrite the destination unless
     * {@code ignoreAirBlocks} is true - usually what you want when pasting
     * onto/into existing terrain (e.g. a bridge butting up against land
     * that's already there).
     */
    public CompletableFuture<Void> paste(Clipboard clipboard, Location location, BlockFace facing, boolean ignoreAirBlocks) {
        return CompletableFuture.runAsync(() -> {
            World weWorld = BukkitAdapter.adapt(location.getWorld());
            try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(weWorld)
                    .fastMode(true)
                    .build()) {
                ClipboardHolder holder = new ClipboardHolder(clipboard);
                double degrees = degreesFor(facing);
                if (degrees != 0) {
                    holder.setTransform(new AffineTransform().rotateY(degrees));
                }

                Operation operation = holder.createPaste(editSession)
                        .to(BlockVector3.at(location.getBlockX(), location.getBlockY(), location.getBlockZ()))
                        .ignoreAirBlocks(ignoreAirBlocks)
                        .build();
                Operations.complete(operation);
            } catch (WorldEditException e) {
                throw new RuntimeException("Failed to paste schematic at " + location, e);
            }
        }, executor);
    }

    /** Loads (or reuses the cache) then pastes in one call - the common case. */
    public CompletableFuture<Void> pasteSchematic(String name, Location location, BlockFace facing, boolean ignoreAirBlocks) {
        return load(name).thenCompose(clipboard -> paste(clipboard, location, facing, ignoreAirBlocks));
    }

    /**
     * Every {@code .schem} file directly inside
     * {@code plugins/yield/schematics/<subfolder>/}, as names already
     * qualified for {@link #load} (e.g. {@code "chunks/cottage"}) - so a
     * caller wanting variety (a random pick each time, say) can just pick
     * randomly from this list rather than hardcoding one name. Adding a
     * new option is then just dropping another file in the folder - no
     * code or config change needed. Empty (not an error) if the subfolder
     * doesn't exist yet or has nothing in it.
     */
    public List<String> listSchematics(String subfolder) {
        File dir = new File(schematicsFolder, subfolder);
        File[] files = dir.listFiles((ignoredDir, name) -> name.endsWith(".schem"));
        if (files == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            String fileName = file.getName();
            names.add(subfolder + "/" + fileName.substring(0, fileName.length() - ".schem".length()));
        }
        return names;
    }

    /** A horizontally-facing {@code OBSERVER} block found in the live world - see {@link #findAnchorsInWorld}. */
    public record WorldAnchor(BlockVector3 position, BlockFace facing) {
    }

    /**
     * Scans real, already-placed blocks within {@code min}..{@code max}
     * (inclusive, in either order) for every horizontally-facing
     * {@code OBSERVER} block, treating each as a connection point - build
     * these into a schematic (pointing the direction something should
     * connect outward in) rather than hardcoding connection coordinates
     * anywhere. Vertically-facing observers (UP/DOWN) are ignored.
     * <p>
     * Reads live blocks, so must be called on the main thread.
     */
    public List<WorldAnchor> findAnchorsInWorld(org.bukkit.World world, BlockVector3 min, BlockVector3 max) {
        int minX = Math.min(min.x(), max.x());
        int maxX = Math.max(min.x(), max.x());
        int minY = Math.min(min.y(), max.y());
        int maxY = Math.max(min.y(), max.y());
        int minZ = Math.min(min.z(), max.z());
        int maxZ = Math.max(min.z(), max.z());

        List<WorldAnchor> anchors = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != Material.OBSERVER) {
                        continue;
                    }
                    if (!(block.getBlockData() instanceof Directional directional)) {
                        continue;
                    }
                    BlockFace facing = directional.getFacing();
                    if (facing != BlockFace.NORTH && facing != BlockFace.EAST
                            && facing != BlockFace.SOUTH && facing != BlockFace.WEST) {
                        continue;
                    }
                    anchors.add(new WorldAnchor(BlockVector3.at(x, y, z), facing));
                }
            }
        }
        return anchors;
    }

    /**
     * The two corners (not necessarily min-then-max - a rotation can flip
     * which is which, so take componentwise min/max of both) that would
     * bound {@code clipboard}'s content, relative to wherever its own
     * origin point gets pasted, once pasted rotated to {@code pasteFacing}.
     * Used to compute a search box for {@link #findAnchorsInWorld} around
     * a paste before knowing exactly where its content landed.
     */
    public BlockVector3[] rotatedRegionCorners(Clipboard clipboard, BlockFace pasteFacing) {
        BlockVector3 origin = clipboard.getOrigin();
        Vector3 min = rotate(clipboard.getRegion().getMinimumPoint().subtract(origin).toVector3(), pasteFacing);
        Vector3 max = rotate(clipboard.getRegion().getMaximumPoint().subtract(origin).toVector3(), pasteFacing);
        return new BlockVector3[] {min.toBlockPoint(), max.toBlockPoint()};
    }

    /** Fills {@code min}..{@code max} (inclusive, either order) with air - removes a provisional paste before re-pasting at a corrected spot. */
    public CompletableFuture<Void> clear(org.bukkit.World world, BlockVector3 min, BlockVector3 max) {
        return CompletableFuture.runAsync(() -> {
            World weWorld = BukkitAdapter.adapt(world);
            CuboidRegion region = new CuboidRegion(weWorld, min, max);
            try (EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                    .world(weWorld)
                    .fastMode(true)
                    .build()) {
                editSession.setBlocks((com.sk89q.worldedit.regions.Region) region, BlockTypes.AIR.getDefaultState());
            } catch (com.sk89q.worldedit.MaxChangedBlocksException e) {
                throw new RuntimeException("Failed to clear region " + min + ".." + max, e);
            }
        }, executor);
    }

    private static Vector3 rotate(Vector3 vector, BlockFace facing) {
        double degrees = degreesFor(facing);
        return degrees == 0 ? vector : new AffineTransform().rotateY(degrees).apply(vector);
    }

    private static double degreesFor(BlockFace facing) {
        return switch (facing) {
            case EAST -> 90;
            case SOUTH -> 180;
            case WEST -> 270;
            default -> 0;
        };
    }

    private File resolveFile(String name) {
        File exact = new File(schematicsFolder, name);
        if (exact.isFile()) {
            return exact;
        }
        File withExtension = new File(schematicsFolder, name + ".schem");
        return withExtension.isFile() ? withExtension : null;
    }

    public void shutdown() {
        executor.shutdown();
    }
}
