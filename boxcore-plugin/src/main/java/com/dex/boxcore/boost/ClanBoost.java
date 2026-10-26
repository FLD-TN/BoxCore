package com.dex.boxcore.boost;

import java.util.UUID;

/**
 * The boost of a clan for one type (Update 05 E3), at most one per clan and type. Real time: it counts down while
 * the members are offline. An extension keeps {@code startedAt} and moves {@code expiresAt}.
 *
 * @param clan      clan id (from the clan plugin)
 * @param type      boost type id
 * @param tenths    multiplier in tenths
 * @param startedAt start instant, epoch milliseconds (UTC)
 * @param expiresAt expiry instant, epoch milliseconds (UTC)
 */
public record ClanBoost(UUID clan, String type, int tenths, long startedAt, long expiresAt) {

    public boolean isExpired(long now) {
        return expiresAt <= now;
    }

    /** Bossbar progress: remaining / total, clamped to [0, 1]. */
    public float progress(long now) {
        return MultiplierCalculator.progress(expiresAt - now, expiresAt - startedAt);
    }

    public ClanBoost withExpiry(long newExpiresAt) {
        return new ClanBoost(clan, type, tenths, startedAt, newExpiresAt);
    }
}
