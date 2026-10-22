package com.dex.boxcore.storage;

import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.boost.BoostRegistry;
import com.dex.boxcore.boost.GlobalBoost;
import com.dex.boxcore.boost.PersonalBoost;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * YAML storage of active boosts (§8): {@code global-boosts.yml} and {@code player-boosts.yml}.
 *
 * <p>Changes mark the data dirty; a save runs at most once per debounce interval. The snapshot is taken on
 * the main thread and written asynchronously with an atomic replace. One save is in flight at a time; a
 * change during a write triggers another save afterwards. {@link #saveSync()} writes the final state on
 * disable.
 */
public final class BoostStorage {

    public static final String GLOBAL_FILE = "global-boosts.yml";
    public static final String PLAYER_FILE = "player-boosts.yml";
    /** Smallest stored multiplier: x1.1. The type's own limits are applied by the effective-multiplier cache. */
    private static final int MIN_TENTHS = BoostType.MIN_DECIMAL_TENTHS;

    private final Plugin plugin;
    private final Logger logger;
    private final BoostRegistry registry;
    private final IntSupplier debounceSeconds;
    private final ErrorReporter errors;
    private final File globalFile;
    private final File playerFile;

    /** Serialises file writes between the async writer and the final synchronous save. */
    private final Object writeLock = new Object();
    /** Sequence of the newest snapshot written, guarded by {@link #writeLock}. */
    private long writtenSeq;
    /** Files that could not be moved aside after a parse error: never overwrite them. */
    private volatile boolean globalBlocked;
    private volatile boolean playerBlocked;

    // main thread state
    private long snapshotSeq;
    private boolean dirty;
    private boolean writing;
    private BukkitTask scheduled;

    public BoostStorage(Plugin plugin, BoostRegistry registry, IntSupplier debounceSeconds, ErrorReporter errors) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.registry = registry;
        this.debounceSeconds = debounceSeconds;
        this.errors = errors;
        this.globalFile = new File(plugin.getDataFolder(), GLOBAL_FILE);
        this.playerFile = new File(plugin.getDataFolder(), PLAYER_FILE);
    }

    // ------------------------------------------------------------------ load

    /** Loads both files into the registry, dropping expired entries (startup, main thread). */
    public void load(long now) {
        boolean dropped = false;

        Map<String, GlobalBoost> globals = new LinkedHashMap<>();
        YamlConfiguration globalYaml = read(globalFile, true);
        if (globalYaml != null) {
            for (String type : globalYaml.getKeys(false)) {
                ConfigurationSection s = globalYaml.getConfigurationSection(type);
                GlobalBoost boost = s == null ? null : parseGlobal(type, s);
                if (boost == null) {
                    logger.warning("[" + GLOBAL_FILE + "] Skipping invalid entry '" + type + "'");
                    dropped = true;
                } else if (boost.isExpired(now)) {
                    dropped = true; // expired while the server was down: removed silently
                } else {
                    globals.put(type, boost);
                }
            }
        }

        Map<UUID, List<PersonalBoost>> personal = new HashMap<>();
        YamlConfiguration playerYaml = read(playerFile, false);
        if (playerYaml != null) {
            for (String key : playerYaml.getKeys(false)) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException ex) {
                    logger.warning("[" + PLAYER_FILE + "] Skipping invalid player id '" + key + "'");
                    dropped = true;
                    continue;
                }
                List<PersonalBoost> list = new ArrayList<>();
                for (Map<?, ?> map : playerYaml.getMapList(key)) {
                    PersonalBoost boost = parsePersonal(map);
                    if (boost == null) {
                        logger.warning("[" + PLAYER_FILE + "] Skipping invalid boost of " + key + ": " + map);
                        dropped = true;
                    } else if (boost.isExpired(now)) {
                        dropped = true;
                    } else {
                        list.add(boost);
                    }
                }
                if (!list.isEmpty()) personal.put(id, list);
            }
        }

        int duplicates = registry.load(globals, personal);
        if (duplicates > 0) {
            logger.info("[" + PLAYER_FILE + "] Kept one bottle per player and type, dropped " + duplicates
                    + " older duplicate(s)");
            dropped = true;
        }
        if (dropped) markDirty();
    }

    /** Reads a YAML file; on a parse error the file is renamed {@code <file>.broken-<timestamp>}. */
    private YamlConfiguration read(File file, boolean global) {
        if (!file.exists()) return null;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            return yaml;
        } catch (IOException | InvalidConfigurationException ex) {
            File broken = new File(file.getParentFile(), file.getName() + ".broken-" + System.currentTimeMillis());
            logger.severe("Could not read " + file.getName() + " (" + ex.getMessage() + "), renaming it to "
                    + broken.getName() + " and starting with no data from it");
            try {
                Files.move(file.toPath(), broken.toPath());
            } catch (IOException moveEx) {
                logger.severe("Could not rename " + file.getName() + ": " + moveEx.getMessage()
                        + ". The file will NOT be overwritten; fix or remove it and restart");
                if (global) globalBlocked = true;
                else playerBlocked = true;
            }
            return null;
        }
    }

    private static GlobalBoost parseGlobal(String type, ConfigurationSection s) {
        if (!(s.get("multiplier") instanceof Number m) || !(s.get("started-at") instanceof Number start)
                || !(s.get("expires-at") instanceof Number end)) {
            return null;
        }
        int tenths = tenths(m);
        if (tenths < MIN_TENTHS || end.longValue() <= start.longValue()) return null;
        return new GlobalBoost(type, tenths, start.longValue(), end.longValue());
    }

    private static PersonalBoost parsePersonal(Map<?, ?> map) {
        if (!(map.get("type") instanceof String type) || !(map.get("multiplier") instanceof Number m)
                || !(map.get("expires-at") instanceof Number end) || !(map.get("total-seconds") instanceof Number total)) {
            return null;
        }
        int tenths = tenths(m);
        if (tenths < MIN_TENTHS || total.longValue() <= 0) return null;
        return new PersonalBoost(type, tenths, end.longValue(), total.longValue());
    }

    /**
     * {@code multiplier} in tenths (Update 04 D6): a YAML integer is a whole multiplier ({@code 3} → 30), a float may
     * have one decimal ({@code 1.5} → 15). @return -1 for anything else ({@code 1.25}, NaN, too large)
     */
    static int tenths(Number m) {
        if (m instanceof Integer || m instanceof Long || m instanceof Short || m instanceof Byte) {
            long whole = m.longValue();
            return whole < 0 || whole > BoostType.LIMIT_TENTHS / Multipliers.ONE ? -1 : (int) whole * Multipliers.ONE;
        }
        double d = m.doubleValue();
        if (!Double.isFinite(d) || d < 0 || d > BoostType.LIMIT_TENTHS / (double) Multipliers.ONE) return -1;
        BigDecimal tenths = BigDecimal.valueOf(d).movePointRight(1).stripTrailingZeros();
        return tenths.scale() > 0 ? -1 : tenths.intValueExact();
    }

    /** Whole multipliers are written as YAML integers (unchanged since 1.0.0), decimal ones as floats. */
    private static Object yamlValue(int tenths) {
        return tenths % Multipliers.ONE == 0 ? (Object) (tenths / Multipliers.ONE) : (Object) (tenths / 10.0);
    }

    // ------------------------------------------------------------------ save

    /** Marks the data changed; schedules a debounced save if none is pending (main thread). */
    public void markDirty() {
        dirty = true;
        if (scheduled == null && !writing && plugin.isEnabled()) {
            scheduled = Bukkit.getScheduler().runTaskLater(plugin, this::flush, debounceSeconds.getAsInt() * 20L);
        }
    }

    private void flush() {
        scheduled = null;
        if (!dirty || writing) return;
        dirty = false;
        Snapshot snapshot = snapshot();
        writing = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                write(snapshot);
            } catch (Throwable t) {
                errors.report("saving boosts", t);
            } finally {
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        writing = false;
                        if (dirty) markDirty();
                    });
                }
            }
        });
    }

    /** Cancels a pending debounced save (the caller saves synchronously right after). */
    public void cancel() {
        if (scheduled != null) {
            scheduled.cancel();
            scheduled = null;
        }
    }

    /** Writes the current state synchronously (onDisable). */
    public void saveSync() {
        cancel();
        dirty = false;
        try {
            write(snapshot());
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.SEVERE, "Could not save boosts on disable", t);
        }
    }

    private record Snapshot(long seq, YamlConfiguration global, YamlConfiguration player) {}

    /** Copies the registry into fresh YAML objects (main thread). */
    private Snapshot snapshot() {
        YamlConfiguration global = new YamlConfiguration();
        for (GlobalBoost b : registry.globals().values()) {
            global.set(b.type() + ".multiplier", yamlValue(b.tenths()));
            global.set(b.type() + ".started-at", b.startedAt());
            global.set(b.type() + ".expires-at", b.expiresAt());
        }
        YamlConfiguration player = new YamlConfiguration();
        for (Map.Entry<UUID, List<PersonalBoost>> e : registry.allPersonal().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<Map<String, Object>> list = new ArrayList<>(e.getValue().size());
            for (PersonalBoost b : e.getValue()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", b.type());
                map.put("multiplier", yamlValue(b.tenths()));
                map.put("expires-at", b.expiresAt());
                map.put("total-seconds", b.totalSeconds());
                list.add(map);
            }
            player.set(e.getKey().toString(), list);
        }
        return new Snapshot(++snapshotSeq, global, player);
    }

    private void write(Snapshot snapshot) throws IOException {
        String globalText = snapshot.global().saveToString();
        String playerText = snapshot.player().saveToString();
        synchronized (writeLock) {
            if (snapshot.seq() <= writtenSeq) return; // a newer state was already written
            Files.createDirectories(plugin.getDataFolder().toPath());
            if (!globalBlocked) atomicWrite(globalFile.toPath(), globalText);
            if (!playerBlocked) atomicWrite(playerFile.toPath(), playerText);
            writtenSeq = snapshot.seq();
        }
    }

    /** Writes {@code <file>.tmp}, flushes it to disk, then atomically replaces the target. */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
