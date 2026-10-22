package com.dex.boxcore.boost;

import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.display.BossBarService;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Coordinates registry, effective cache and bossbars: every change to boosts goes through here so the
 * cache and the bars are recomputed on exactly the triggers of §7.1. Main thread only.
 */
public final class BoostController implements Listener {

    private final Supplier<CoreConfig> config;
    private final Supplier<Messages> messages;
    private final BoostRegistry registry;
    private final EffectiveCache cache;
    private final BossBarService bars;
    private final Messages.Throttle throttle;
    private final ErrorReporter errors;
    private final Executor nextTick;

    /** @param nextTick runs a task on the main thread at the next tick (drops it once the plugin is disabled) */
    public BoostController(Supplier<CoreConfig> config, Supplier<Messages> messages, Messages.Throttle throttle,
                           BoostRegistry registry, EffectiveCache cache, BossBarService bars, ErrorReporter errors,
                           Executor nextTick) {
        this.config = config;
        this.messages = messages;
        this.registry = registry;
        this.cache = cache;
        this.bars = bars;
        this.throttle = throttle;
        this.errors = errors;
        this.nextTick = nextTick;
    }

    public BoostRegistry registry() {
        return registry;
    }

    public EffectiveCache cache() {
        return cache;
    }

    // ------------------------------------------------------------------ recompute

    /** Recomputes one player's effective multipliers and personal bars. */
    public EffectiveCache.Snapshot refresh(Player player, long now) {
        EffectiveCache.Snapshot snapshot = cache.recompute(player, config.get(), registry, now);
        bars.updatePlayer(player, snapshot, registry, now);
        return snapshot;
    }

    /** Recomputes every online player (global change, reload, permission refresh). */
    public void refreshAll(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                refresh(player, now);
            } catch (Throwable t) {
                errors.report("refreshing " + player.getName(), t);
            }
        }
    }

    /** Drops and rebuilds every bar (reload: colours and templates may have changed). */
    public void rebuildDisplay(long now) {
        bars.clear();
        bars.syncGlobals(registry, now);
        for (Player player : Bukkit.getOnlinePlayers()) bars.join(player);
        refreshAll(now);
    }

    // ------------------------------------------------------------------ global

    /** Starts or replaces the global boost of a type. @return the replaced boost, or {@code null} */
    public GlobalBoost startGlobal(BoostType type, int tenths, long durationSeconds, long now) {
        GlobalBoost old = registry.putGlobal(new GlobalBoost(type.id(), tenths, now, now + durationSeconds * 1000L));
        bars.syncGlobals(registry, now);
        refreshAll(now);
        return old != null && !old.isExpired(now) ? old : null;
    }

    /** Stops the global boost of a type. @return the stopped boost, or {@code null} if none was active */
    public GlobalBoost stopGlobal(String type, long now) {
        GlobalBoost old = registry.removeGlobal(type);
        if (old == null) return null;
        bars.syncGlobals(registry, now);
        refreshAll(now);
        return old;
    }

    // ------------------------------------------------------------------ personal

    /**
     * Stores the player's bottle of a type (replacing any previous one of that type) and recomputes the player.
     *
     * @return the replaced bottle if it was still active, otherwise {@code null}
     */
    public PersonalBoost setPersonal(Player player, PersonalBoost boost, long now) {
        PersonalBoost replaced = registry.setPersonal(player.getUniqueId(), boost);
        refresh(player, now);
        return replaced != null && !replaced.isExpired(now) ? replaced : null;
    }

    // ------------------------------------------------------------------ tick (§7.2 update task)

    /** Removes expired boosts, notifies, recomputes and updates bar texts. Called every 20 ticks. */
    public void tick(long now) {
        Messages msg = messages.get();
        CoreConfig cfg = config.get();

        List<GlobalBoost> expiredGlobals = registry.expireGlobals(now);
        for (GlobalBoost b : expiredGlobals) {
            BoostType type = cfg.type(b.type());
            msg.broadcast(type, "global.stopped", "type", b.type(), "type_name", type == null ? b.type() : type.displayName());
        }

        Map<UUID, List<PersonalBoost>> expiredPersonal = registry.expirePersonal(now);
        for (Map.Entry<UUID, List<PersonalBoost>> e : expiredPersonal.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) continue; // offline: removed silently
            for (PersonalBoost b : e.getValue()) {
                BoostType type = cfg.type(b.type());
                if (type == null) continue;
                msg.notify(player, type, "bottle.expired", "type", type.id(), "type_name", type.displayName(),
                        "multiplier", msg.multiplier(b.tenths()));
                msg.title(player, type, "bottle.expired-title", "bottle.expired-subtitle", "type", type.id(),
                        "type_name", type.displayName(), "multiplier", msg.multiplier(b.tenths()));
            }
            if (expiredGlobals.isEmpty()) refresh(player, now);
        }

        if (!expiredGlobals.isEmpty()) {
            bars.syncGlobals(registry, now);
            refreshAll(now);
        }
        bars.tick(now);
    }

    // ------------------------------------------------------------------ sessions

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        try {
            join(event.getPlayer());
        } catch (Throwable t) {
            errors.report("player join", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        try {
            quit(event.getPlayer());
        } catch (Throwable t) {
            errors.report("player quit", t);
        }
    }

    public void join(Player player) {
        bars.join(player);
        refresh(player, System.currentTimeMillis());
    }

    /**
     * Bars are hidden at once. The multipliers stay in the cache until the next tick, so other plugins' quit
     * handlers (which may run after BoxCore's) still read the player's real multiplier; they are then dropped
     * unless the player is online again.
     */
    public void quit(Player player) {
        UUID id = player.getUniqueId();
        bars.quit(player);
        throttle.forget(id);
        nextTick.execute(() -> {
            if (Bukkit.getPlayer(id) == null) cache.remove(id);
        });
    }
}
