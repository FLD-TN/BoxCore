package com.dex.boxcore.boost;

import com.dex.boxcore.api.ClanBoostResult;
import com.dex.boxcore.api.event.ClanBoostEvent;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.display.BossBarService;
import com.dex.boxcore.util.DurationFormat;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
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
    private final ClanLink clans;
    /** Fires Bukkit events; a no-op until the plugin sets it (unit tests have no server). */
    private Consumer<org.bukkit.event.Event> events = e -> {};

    /** @param nextTick runs a task on the main thread at the next tick (drops it once the plugin is disabled) */
    public BoostController(Supplier<CoreConfig> config, Supplier<Messages> messages, Messages.Throttle throttle,
                           BoostRegistry registry, EffectiveCache cache, BossBarService bars, ErrorReporter errors,
                           Executor nextTick, ClanLink clans) {
        this.config = config;
        this.messages = messages;
        this.registry = registry;
        this.cache = cache;
        this.bars = bars;
        this.throttle = throttle;
        this.errors = errors;
        this.nextTick = nextTick;
        this.clans = clans;
    }

    public ClanLink clans() {
        return clans;
    }

    /** Where {@link ClanBoostEvent}s go (Bukkit's plugin manager in the plugin). */
    public void events(Consumer<org.bukkit.event.Event> events) {
        this.events = events == null ? e -> {} : events;
    }

    private void fire(ClanBoost boost, ClanBoostEvent.Action action, int oldTenths, long addedSeconds, String actor) {
        try {
            events.accept(new ClanBoostEvent(boost.clan(), boost.type(), action, boost.tenths(), oldTenths,
                    boost.expiresAt(), addedSeconds, actor));
        } catch (Throwable t) {
            errors.report("clan boost event", t);
        }
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
        EffectiveCache.Snapshot snapshot = cache.recompute(player, config.get(), registry, clans, now);
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

    /**
     * Moves the expiry of the player's running bottle (same multiplier, Update 05 E5). Its start stays, so the
     * bossbar progress never goes above 1.
     */
    public void extendPersonal(Player player, PersonalBoost running, long newExpiresAt, long now) {
        long startedAt = running.expiresAt() - running.totalSeconds() * 1000L;
        long total = Math.max(1, (newExpiresAt - startedAt + 999) / 1000);
        registry.setPersonal(player.getUniqueId(), new PersonalBoost(running.type(), running.tenths(), newExpiresAt, total));
        refresh(player, now);
    }

    // ------------------------------------------------------------------ clan (Update 05 E3)

    /** Recomputes one player if online (their clan changed). */
    public void refreshPlayer(UUID id, long now) {
        Player player = id == null ? null : Bukkit.getPlayer(id);
        if (player != null) refresh(player, now);
    }

    private void refreshMembers(Collection<UUID> members, long now) {
        for (UUID id : members) {
            try {
                refreshPlayer(id, now);
            } catch (Throwable t) {
                errors.report("refreshing clan member " + id, t);
            }
        }
    }

    /** The clan's running boost of a type, {@code null} if none. */
    public ClanBoost clanBoost(UUID clan, String type, long now) {
        ClanBoost b = registry.clan(clan, type);
        return b == null || b.isExpired(now) ? null : b;
    }

    /**
     * Starts, extends or replaces a clan's boost (Update 05 E3.3) and tells the online members.
     *
     * @param actor name shown to the clan, {@code null} for staff
     */
    public ClanBoostResult startClan(BoostType type, UUID clan, int tenths, long seconds, boolean confirmed,
                                     String actor, long now) {
        if (clan == null || !clans.available()) return ClanBoostResult.of(ClanBoostResult.Status.UNAVAILABLE);
        if (!type.isValidMultiplier(tenths) || !type.isValidDuration(seconds)) {
            return ClanBoostResult.of(ClanBoostResult.Status.INVALID);
        }
        long durationMs = seconds * 1000L;
        ClanBoost old = clanBoost(clan, type.id(), now);
        if (old == null) {
            ClanBoost started = new ClanBoost(clan, type.id(), tenths, now, now + durationMs);
            registry.putClan(started);
            announce(clan, type, "clan.started", actor, tenths, seconds, 0, started.expiresAt(), now);
            fire(started, ClanBoostEvent.Action.STARTED, 0, seconds, actor);
            return new ClanBoostResult(ClanBoostResult.Status.STARTED, 0, 0, started.expiresAt(), 0);
        }
        if (tenths < old.tenths()) {
            return new ClanBoostResult(ClanBoostResult.Status.LOWER, old.tenths(), old.expiresAt(), old.expiresAt(), 0);
        }
        if (tenths == old.tenths()) {
            MultiplierCalculator.Extension e = MultiplierCalculator.extend(old.expiresAt(), durationMs, now,
                    type.maxDurationSeconds() * 1000L);
            long lost = (e.lostMs() + 999) / 1000;
            if (e.full()) {
                return new ClanBoostResult(ClanBoostResult.Status.FULL, old.tenths(), old.expiresAt(), old.expiresAt(), lost);
            }
            if (lost > 0 && !confirmed) {
                return new ClanBoostResult(ClanBoostResult.Status.CONFIRM_CAPPED, old.tenths(), old.expiresAt(),
                        e.expiresAt(), lost);
            }
            ClanBoost extended = old.withExpiry(e.expiresAt());
            registry.putClan(extended);
            announce(clan, type, "clan.extended", actor, tenths, seconds, (e.expiresAt() - old.expiresAt()) / 1000,
                    e.expiresAt(), now);
            fire(extended, ClanBoostEvent.Action.EXTENDED, 0, (e.expiresAt() - old.expiresAt()) / 1000, actor);
            return new ClanBoostResult(ClanBoostResult.Status.EXTENDED, old.tenths(), old.expiresAt(), e.expiresAt(), lost);
        }
        long remaining = DurationFormat.remainingSeconds(old.expiresAt(), now);
        if (!confirmed) {
            return new ClanBoostResult(ClanBoostResult.Status.CONFIRM_REPLACE, old.tenths(), old.expiresAt(), 0, remaining);
        }
        ClanBoost replacement = new ClanBoost(clan, type.id(), tenths, now, now + durationMs);
        registry.putClan(replacement);
        announce(clan, type, "clan.replaced", actor, tenths, seconds, 0, replacement.expiresAt(), now,
                "old", messages.get().multiplier(old.tenths()));
        fire(replacement, ClanBoostEvent.Action.REPLACED, old.tenths(), seconds, actor);
        return new ClanBoostResult(ClanBoostResult.Status.REPLACED, old.tenths(), old.expiresAt(),
                replacement.expiresAt(), remaining);
    }

    /** Stops a clan's boost of a type and tells the online members. @return false if none was running */
    public boolean stopClan(UUID clan, String typeId, long now) {
        ClanBoost removed = registry.removeClan(clan, typeId);
        if (removed == null) return false;
        fire(removed.withExpiry(now), ClanBoostEvent.Action.STOPPED, 0, 0, null);
        Collection<UUID> members = clans.onlineMembers(clan);
        refreshMembers(members, now);
        BoostType type = config.get().type(typeId);
        Messages msg = messages.get();
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) msg.notify(p, type, "clan.stopped", "type", typeId,
                    "type_name", type == null ? typeId : type.displayName(), "multiplier", msg.multiplier(removed.tenths()));
        }
        return true;
    }

    /** Deletes every boost of a disbanded clan, silently, and recomputes every online player. */
    public void clearClan(UUID clan, long now) {
        if (registry.removeClan(clan)) refreshAll(now);
    }

    /** Recomputes the clan's online members, then tells them (chat) what changed. */
    private void announce(UUID clan, BoostType type, String key, String actor, int tenths, long seconds, long added,
                          long expiresAt, long now, Object... extra) {
        Collection<UUID> members = clans.onlineMembers(clan);
        refreshMembers(members, now);
        Messages msg = messages.get();
        Object[] base = {"player", actor == null ? msg.raw("clan.staff-name") : actor,
                "clan", clans.clanName(clan),
                "type", type.id(), "type_name", type.displayName(),
                "multiplier", msg.multiplier(tenths),
                "duration", msg.duration(seconds),
                "added", msg.duration(Math.max(added, 0)),
                "remaining", msg.duration(DurationFormat.remainingSeconds(expiresAt, now))};
        Object[] all = new Object[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) msg.notify(p, type, key, all);
        }
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

        List<ClanBoost> expiredClans = registry.expireClans(now);
        for (ClanBoost b : expiredClans) {
            fire(b, ClanBoostEvent.Action.EXPIRED, 0, 0, null);
            BoostType type = cfg.type(b.type());
            Collection<UUID> members = clans.onlineMembers(b.clan());
            if (expiredGlobals.isEmpty()) refreshMembers(members, now);
            if (type == null) continue;
            for (UUID id : members) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) msg.notify(p, type, "clan.expired", "type", type.id(), "type_name", type.displayName(),
                        "multiplier", msg.multiplier(b.tenths()));
            }
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

    /** The clan plugin was disabled: no clan boosts until it registers again (Update 05 E3.2). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        try {
            if (clans.removeIfOwner(event.getPlugin())) refreshAll(System.currentTimeMillis());
        } catch (Throwable t) {
            errors.report("plugin disable", t);
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
