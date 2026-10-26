package com.dex.boxcore.boost;

import com.dex.boxcore.api.ActiveBoost;
import com.dex.boxcore.api.BoostService;
import com.dex.boxcore.api.BottleInfo;
import com.dex.boxcore.api.ClanBoostResult;
import com.dex.boxcore.api.ClanProvider;
import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.item.BoostItemReader;
import com.dex.boxcore.item.BottleData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * {@link BoostService} implementation: multipliers are read from the effective-multiplier cache only. Clan boost calls
 * (Update 05 E3) go to the {@link BoostController} on the main thread. Never throws.
 */
public final class BoostServiceImpl implements BoostService {

    private static final long NULL_LOG_INTERVAL_MS = 60_000L;

    private final EffectiveCache cache;
    private final Supplier<CoreConfig> config;
    private final Logger logger;
    private final AtomicLong lastNullLog = new AtomicLong(Long.MIN_VALUE / 2);
    /** {@code null} in the read-only form (tests): every clan call answers "unavailable". */
    private final BoostController controller;
    private final BoostItemReader reader;
    private final Executor mainThread;
    private final BooleanSupplier isMainThread;

    /** Read-only service: multipliers only. */
    public BoostServiceImpl(EffectiveCache cache, Supplier<CoreConfig> config, Logger logger) {
        this(cache, config, logger, null, null, Runnable::run, () -> false);
    }

    /**
     * @param mainThread   runs a task on the main thread (next tick)
     * @param isMainThread true when called on the main thread
     */
    public BoostServiceImpl(EffectiveCache cache, Supplier<CoreConfig> config, Logger logger,
                            BoostController controller, BoostItemReader reader, Executor mainThread,
                            BooleanSupplier isMainThread) {
        this.cache = cache;
        this.config = config;
        this.logger = logger;
        this.controller = controller;
        this.reader = reader;
        this.mainThread = mainThread;
        this.isMainThread = isMainThread;
    }

    @Override
    public int getMultiplier(UUID playerId, String type) {
        return getMultiplierTenths(playerId, type) / Multipliers.ONE;
    }

    @Override
    public int getMultiplierTenths(UUID playerId, String type) {
        try {
            if (playerId == null || type == null) {
                logNull(playerId, type);
                return Multipliers.ONE;
            }
            int m = cache.get(playerId, type);
            return m < Multipliers.ONE ? Multipliers.ONE : m;
        } catch (Throwable t) {
            return Multipliers.ONE;
        }
    }

    @Override
    public double getMultiplierExact(UUID playerId, String type) {
        return getMultiplierTenths(playerId, type) / 10.0;
    }

    @Override
    public long multiply(UUID playerId, String type, long amount) {
        return Multipliers.floor(amount, getMultiplierTenths(playerId, type));
    }

    @Override
    public boolean isType(String type) {
        try {
            return type != null && config.get().type(type) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ clan boosts (Update 05 E3)

    @Override
    public void registerClanProvider(Plugin owner, ClanProvider provider) {
        if (controller == null || owner == null || provider == null) return;
        onMain(() -> {
            controller.clans().set(owner, provider);
            controller.refreshAll(System.currentTimeMillis());
            logger.info("Clan boosts enabled: clans provided by " + owner.getName());
        });
    }

    @Override
    public void refreshClanOf(UUID player) {
        if (controller == null || player == null) return;
        onMain(() -> controller.refreshPlayer(player, System.currentTimeMillis()));
    }

    @Override
    public void clearClan(UUID clan) {
        if (controller == null || clan == null) return;
        onMain(() -> controller.clearClan(clan, System.currentTimeMillis()));
    }

    @Override
    public ClanBoostResult startClanBoost(UUID clan, String type, int tenths, long seconds, boolean confirmed,
                                          String actor) {
        try {
            if (controller == null || !isMainThread.getAsBoolean()) {
                return ClanBoostResult.of(ClanBoostResult.Status.UNAVAILABLE);
            }
            BoostType t = type == null ? null : config.get().type(type);
            if (t == null) return ClanBoostResult.of(ClanBoostResult.Status.INVALID);
            return controller.startClan(t, clan, tenths, seconds, confirmed, actor, System.currentTimeMillis());
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.WARNING, "startClanBoost failed", t);
            return ClanBoostResult.of(ClanBoostResult.Status.UNAVAILABLE);
        }
    }

    @Override
    public boolean stopClanBoost(UUID clan, String type) {
        try {
            if (controller == null || clan == null || type == null || !isMainThread.getAsBoolean()) return false;
            return controller.stopClan(clan, type, System.currentTimeMillis());
        } catch (Throwable t) {
            logger.log(java.util.logging.Level.WARNING, "stopClanBoost failed", t);
            return false;
        }
    }

    @Override
    public Optional<ActiveBoost> clanBoost(UUID clan, String type) {
        try {
            if (controller == null || clan == null || type == null || !isMainThread.getAsBoolean()) return Optional.empty();
            ClanBoost b = controller.clanBoost(clan, type, System.currentTimeMillis());
            return b == null ? Optional.empty()
                    : Optional.of(new ActiveBoost(b.type(), b.tenths(), b.startedAt(), b.expiresAt()));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<BottleInfo> readBottle(ItemStack item) {
        try {
            if (reader == null) return Optional.empty();
            BottleData data = reader.read(item);
            return data == null ? Optional.empty()
                    : Optional.of(new BottleInfo(data.type(), data.tenths(), data.durationSeconds(), data.clan()));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** Runs now on the main thread, otherwise at the next tick. */
    private void onMain(Runnable task) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.log(java.util.logging.Level.WARNING, "Clan boost call failed", t);
            }
        };
        if (isMainThread.getAsBoolean()) guarded.run();
        else mainThread.execute(guarded);
    }

    private void logNull(UUID playerId, String type) {
        long now = System.currentTimeMillis();
        long prev = lastNullLog.get();
        if (now - prev >= NULL_LOG_INTERVAL_MS && lastNullLog.compareAndSet(prev, now)) {
            logger.warning("BoostService#getMultiplier called with a null argument (player=" + playerId
                    + ", type=" + type + "), returning x1. Further occurrences are suppressed for 1 minute");
        }
    }
}
