package com.dex.boxcore.boost;

import com.dex.boxcore.api.BoostService;
import com.dex.boxcore.config.CoreConfig;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** {@link BoostService} implementation: reads the effective-multiplier cache only. Never throws. */
public final class BoostServiceImpl implements BoostService {

    private static final long NULL_LOG_INTERVAL_MS = 60_000L;

    private final EffectiveCache cache;
    private final Supplier<CoreConfig> config;
    private final Logger logger;
    private final AtomicLong lastNullLog = new AtomicLong(Long.MIN_VALUE / 2);

    public BoostServiceImpl(EffectiveCache cache, Supplier<CoreConfig> config, Logger logger) {
        this.cache = cache;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public int getMultiplier(UUID playerId, String type) {
        try {
            if (playerId == null || type == null) {
                logNull(playerId, type);
                return 1;
            }
            int m = cache.get(playerId, type);
            return m < 1 ? 1 : m;
        } catch (Throwable t) {
            return 1;
        }
    }

    @Override
    public boolean isType(String type) {
        try {
            return type != null && config.get().type(type) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void logNull(UUID playerId, String type) {
        long now = System.currentTimeMillis();
        long prev = lastNullLog.get();
        if (now - prev >= NULL_LOG_INTERVAL_MS && lastNullLog.compareAndSet(prev, now)) {
            logger.warning("BoostService#getMultiplier called with a null argument (player=" + playerId
                    + ", type=" + type + "), returning 1. Further occurrences are suppressed for 1 minute");
        }
    }
}
