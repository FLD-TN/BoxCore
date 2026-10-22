package com.dex.boxcore;

import com.dex.boxcore.api.BoostService;
import com.dex.boxcore.api.GemService;
import com.dex.boxcore.boost.BoostController;
import com.dex.boxcore.boost.BoostRegistry;
import com.dex.boxcore.boost.BoostServiceImpl;
import com.dex.boxcore.boost.EffectiveCache;
import com.dex.boxcore.command.BoosterCommand;
import com.dex.boxcore.command.BoxCoreCommand;
import com.dex.boxcore.command.GemCommand;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.display.BossBarService;
import com.dex.boxcore.display.BoxCoreExpansion;
import com.dex.boxcore.gem.GemDatabase;
import com.dex.boxcore.gem.GemLedger;
import com.dex.boxcore.gem.GemServiceImpl;
import com.dex.boxcore.gem.GemSessionListener;
import com.dex.boxcore.gem.GemTop;
import com.dex.boxcore.item.BoostItemFactory;
import com.dex.boxcore.item.BoostItemReader;
import com.dex.boxcore.item.BottleKeys;
import com.dex.boxcore.item.BottleListener;
import com.dex.boxcore.storage.BoostStorage;
import com.dex.boxcore.task.PermissionRefreshTask;
import com.dex.boxcore.task.TickTask;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** BoxCore entry point: wiring only. */
public final class BoxCorePlugin extends JavaPlugin {

    private volatile CoreConfig config;
    private volatile Messages messages;
    private final Messages.Throttle throttle = new Messages.Throttle();

    private ErrorReporter errors;
    private BoostStorage storage;
    private BoostController controller;
    private BossBarService bars;
    private EffectiveCache cache;
    private BukkitTask tickTask;
    private BukkitTask permissionTask;
    private Object expansion;

    private GemDatabase gemDb;
    private GemLedger gems;
    private GemTop gemTop;
    private BukkitTask gemTopTask;
    private int gemTopPeriod;

    @Override
    public void onEnable() {
        errors = new ErrorReporter(getLogger());
        saveDefaultFiles();

        config = loadConfigAtStartup();
        messages = loadMessagesAtStartup(config);

        long now = System.currentTimeMillis();
        BoostRegistry registry = new BoostRegistry();
        storage = new BoostStorage(this, registry, () -> config.saveDebounceSeconds(), errors);
        storage.load(now);
        registry.onChange(storage::markDirty);

        cache = new EffectiveCache();
        bars = new BossBarService(this::coreConfig, this::messages);
        controller = new BoostController(this::coreConfig, this::messages, throttle, registry, cache, bars, errors,
                this::runOnMain);

        BottleKeys keys = BottleKeys.of(this);
        BoostItemFactory factory = new BoostItemFactory(keys);
        BoostItemReader reader = new BoostItemReader(keys);

        Bukkit.getServicesManager().register(BoostService.class,
                new BoostServiceImpl(cache, this::coreConfig, getLogger()), this, ServicePriority.Normal);

        // gems (Update 03): storage, ledger, API; a null database disables the feature, boosts keep working
        gemDb = GemDatabase.open(new File(getDataFolder(), GemDatabase.FILE), getLogger());
        gems = new GemLedger(() -> config.gem(), gemDb, this::runOnMain, id -> Bukkit.getPlayer(id) != null,
                System::currentTimeMillis, getLogger());
        gemTop = new GemTop(gems, System::currentTimeMillis, getLogger());
        pruneGemLog(now);
        Bukkit.getServicesManager().register(GemService.class,
                new GemServiceImpl(gems, Bukkit::isPrimaryThread), this, ServicePriority.Normal);
        Map<UUID, Long> payCooldowns = GemSessionListener.newCooldownMap();

        Bukkit.getPluginManager().registerEvents(controller, this);
        Bukkit.getPluginManager().registerEvents(new GemSessionListener(gems, payCooldowns, errors), this);
        Bukkit.getPluginManager().registerEvents(
                new BottleListener(this::coreConfig, this::messages, controller, reader, errors), this);
        register("boxcore", new BoxCoreCommand(this::coreConfig, this::messages, controller, factory, this::reload,
                gems, errors));
        register("booster", new BoosterCommand(this::coreConfig, this::messages, errors));
        register("gem", new GemCommand(this::messages, gems, gemTop, payCooldowns, errors));

        bars.syncGlobals(registry, now);
        for (Player player : Bukkit.getOnlinePlayers()) controller.join(player);
        Map<UUID, String> online = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) online.put(player.getUniqueId(), player.getName());
        gems.loadOnline(online);

        tickTask = Bukkit.getScheduler().runTaskTimer(this, new TickTask(controller, errors),
                TickTask.PERIOD_TICKS, TickTask.PERIOD_TICKS);
        // every tick, a few players at a time; reads permission-refresh-seconds each cycle (no reschedule on reload)
        permissionTask = Bukkit.getScheduler().runTaskTimer(this,
                new PermissionRefreshTask(controller, () -> config.permissionRefreshSeconds(), errors), 1L, 1L);
        scheduleGemTop();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            registerExpansion();
        }
        getLogger().info("Enabled with " + config.types().size() + " boost type(s): " + config.types().keySet()
                + (gemDb != null ? ", gems enabled" : ", gems DISABLED (see the errors above)"));
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (tickTask != null) tickTask.cancel();
        if (permissionTask != null) permissionTask.cancel();
        if (gemTopTask != null) gemTopTask.cancel();
        Bukkit.getScheduler().cancelTasks(this);
        if (gems != null) gems.shutdown(); // stale GemService references now refuse every change
        if (gemDb != null) {
            gemDb.close(); // runs every queued gem write to completion (Update 03 §3.5)
            gemDb = null;
        }
        try {
            if (bars != null) bars.clear();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Could not hide bossbars", t);
        }
        if (storage != null) storage.saveSync();
        if (expansion != null) {
            try {
                ((BoxCoreExpansion) expansion).unregister();
            } catch (Throwable ignored) {
                // PlaceholderAPI may already be disabled
            }
            expansion = null;
        }
        if (cache != null) cache.clear();
        throttle.clear();
    }

    public CoreConfig coreConfig() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    // ------------------------------------------------------------------ reload

    /** {@code /boxcore reload}: parse into new objects first, swap only if parsing succeeded. */
    private void reload(CommandSender sender) {
        CoreConfig newConfig;
        Messages newMessages;
        try {
            saveDefaultFiles();
            newConfig = CoreConfig.load(new File(getDataFolder(), CoreConfig.FILE), getLogger());
            try (InputStream defaults = getResource(Messages.FILE)) {
                newMessages = Messages.load(new File(getDataFolder(), Messages.FILE), defaults, newConfig,
                        getLogger(), throttle);
            }
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Reload failed, keeping the previous configuration", ex);
            messages.send(sender, "reload.failed");
            return;
        }
        config = newConfig;
        messages = newMessages;
        controller.rebuildDisplay(System.currentTimeMillis());
        if (gemTopPeriod != newConfig.gem().topRefreshSeconds()) scheduleGemTop();
        else gemTop.refresh(); // size or exclusions may have changed
        messages.send(sender, "reload.done");
        getLogger().info("Reloaded with " + newConfig.types().size() + " boost type(s): " + newConfig.types().keySet());
    }

    // ------------------------------------------------------------------ startup loading

    /** Current format of config.yml and messages.yml (1.1.0: stacking rules, brighter palette, per-type colours). */
    private static final int FILE_VERSION = 2;

    /** Writes missing files; backs up and replaces files of an older format (1.0.0 has no version key). */
    private void saveDefaultFiles() {
        for (String name : new String[]{CoreConfig.FILE, Messages.FILE}) {
            File file = new File(getDataFolder(), name);
            if (file.exists()) {
                YamlConfiguration yaml = new YamlConfiguration();
                try {
                    yaml.load(file);
                } catch (IOException | InvalidConfigurationException ex) {
                    continue; // unreadable: reported by the loader, never overwritten here
                }
                if (yaml.getInt(Messages.VERSION_KEY, 1) >= FILE_VERSION) continue;
                File backup = new File(getDataFolder(), name + ".1.0.0.bak");
                if (backup.exists()) backup = new File(getDataFolder(), name + ".1.0.0-" + System.currentTimeMillis() + ".bak");
                if (!file.renameTo(backup)) {
                    getLogger().severe("Could not back up the old " + name + ", keeping it; new defaults are NOT applied");
                    continue;
                }
                getLogger().warning(name + " was written by BoxCore 1.0.0: saved as " + backup.getName()
                        + " and replaced by the 1.1.0 defaults (new rules, colours and texts). Re-apply your own changes");
            }
            saveResource(name, false);
        }
    }

    private CoreConfig loadConfigAtStartup() {
        try {
            return CoreConfig.load(new File(getDataFolder(), CoreConfig.FILE), getLogger());
        } catch (IOException | InvalidConfigurationException ex) {
            getLogger().log(Level.SEVERE, "Could not read " + CoreConfig.FILE + ", using the bundled defaults "
                    + "(the file is left untouched, fix it and run /boxcore reload)", ex);
            return CoreConfig.parse(bundled(CoreConfig.FILE), getLogger());
        }
    }

    private Messages loadMessagesAtStartup(CoreConfig cfg) {
        YamlConfiguration defaults = bundled(Messages.FILE);
        YamlConfiguration yaml;
        try {
            yaml = new YamlConfiguration();
            yaml.load(new File(getDataFolder(), Messages.FILE));
        } catch (IOException | InvalidConfigurationException ex) {
            getLogger().log(Level.SEVERE, "Could not read " + Messages.FILE + ", using the bundled defaults "
                    + "(the file is left untouched, fix it and run /boxcore reload)", ex);
            yaml = defaults;
        }
        return Messages.parse(yaml, defaults, cfg, getLogger(), throttle);
    }

    private YamlConfiguration bundled(String name) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = getResource(name); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (IOException | InvalidConfigurationException | NullPointerException ex) {
            throw new IllegalStateException("Bundled " + name + " is missing or invalid", ex);
        }
        return yaml;
    }

    // ------------------------------------------------------------------ helpers

    /** Leaderboard refresh (Update 03 §5.3) and cleanup of pre-login loads whose player never joined. */
    private void scheduleGemTop() {
        if (gemTopTask != null) gemTopTask.cancel();
        gemTopPeriod = config.gem().topRefreshSeconds();
        long period = gemTopPeriod * 20L;
        gemTopTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                gemTop.refresh();
                gems.purgePending();
            } catch (Throwable t) {
                errors.report("gem leaderboard refresh", t);
            }
        }, 1L, period);
    }

    /** Deletes transactions older than gem.log-keep-days, on the DB thread (Update 03 §2). */
    private void pruneGemLog(long now) {
        int days = config.gem().logKeepDays();
        if (gemDb == null || days <= 0) return;
        long before = now - days * 86_400_000L;
        gemDb.submit(c -> GemDatabase.prune(c, before)).whenComplete((removed, error) -> {
            if (error != null) getLogger().log(Level.WARNING, "Could not prune old gem transactions", error);
            else if (removed > 0) getLogger().info("Pruned " + removed + " gem transaction(s) older than " + days + " days");
        });
    }

    /** Main-thread executor for gem callbacks; drops tasks once the plugin is disabled. */
    private void runOnMain(Runnable task) {
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(this, task);
        } catch (IllegalPluginAccessException ignored) {
            // disabled between the check and the call
        }
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void registerExpansion() {
        try {
            BoxCoreExpansion created = new BoxCoreExpansion(cache, gems, gemTop, this::messages,
                    getPluginMeta().getVersion());
            if (created.register()) {
                expansion = created;
                getLogger().info("Registered PlaceholderAPI expansion 'boxcore'");
            } else {
                getLogger().warning("Could not register the PlaceholderAPI expansion 'boxcore'");
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Could not register the PlaceholderAPI expansion", t);
        }
    }
}
