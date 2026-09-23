package me.dontshare.yieldcore.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * Keeps a plugin's content files (zones.yml, blocktree.yml, tools.yml...)
 * in step with the jar that ships them.
 * <p>
 * Bukkit's {@code saveResource(name, false)} copies a default once and
 * never again, so every balance or content change in a new build was
 * silently ignored by a server that already had the old file. This
 * remembers a fingerprint of the bundled copy it last installed (in
 * {@code .bundled.yml} next to the files) and, when a build ships a
 * different one:
 * <ul>
 *   <li>the server copy is still exactly what was installed - it is
 *       replaced, quietly;</li>
 *   <li>the server copy was edited (or predates this tracking) - it is
 *       kept as {@code name.old-<timestamp>}, the new one is installed, and
 *       the console says so.</li>
 * </ul>
 * A build that ships the same file as last time changes nothing, so an
 * edit made on the server lasts until the next build that changes that
 * file. Files the plugin itself writes at runtime (spawn.yml,
 * mining-spots.yml) must not go through here - they aren't content.
 */
public final class BundledConfig {

    private static final String LEDGER = ".bundled.yml";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private BundledConfig() {
    }

    /** Makes sure {@code name} exists in the plugin's data folder and matches the bundled version - see the class notes. Returns the file. */
    public static synchronized File sync(JavaPlugin plugin, String name) {
        File target = new File(plugin.getDataFolder(), name);
        byte[] bundled;
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return target;
            }
            bundled = in.readAllBytes();
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't read bundled " + name + ": " + e.getMessage());
            return target;
        }
        String bundledHash = sha256(bundled);

        File ledgerFile = new File(plugin.getDataFolder(), LEDGER);
        YamlConfiguration ledger = YamlConfiguration.loadConfiguration(ledgerFile);
        String recorded = ledger.getString(key(name));

        try {
            if (!target.exists()) {
                write(target, bundled);
            } else if (bundledHash.equals(recorded)) {
                return target;
            } else {
                String serverHash = sha256(Files.readAllBytes(target.toPath()));
                if (!serverHash.equals(bundledHash)) {
                    if (recorded != null && serverHash.equals(recorded)) {
                        plugin.getLogger().info(name + " updated to the version in this build.");
                    } else {
                        File backup = new File(plugin.getDataFolder(), name + ".old-" + LocalDateTime.now().format(STAMP));
                        Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        plugin.getLogger().warning(name + " on this server differed from the version in this build - "
                                + "installed the new one; the old copy is " + backup.getName() + ".");
                    }
                    write(target, bundled);
                }
            }
            ledger.set(key(name), bundledHash);
            ledger.save(ledgerFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't update " + name + ": " + e.getMessage());
        }
        return target;
    }

    private static void write(File target, byte[] bytes) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(target.toPath(), bytes);
    }

    /** Dots would nest in YAML; file names are flat, so they're escaped. */
    private static String key(String name) {
        return name.replace('.', '_').replace('/', '_');
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
