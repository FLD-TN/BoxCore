package com.dex.boxcore.boost;

/**
 * The server-wide boost of a type (at most one per type). Stored as absolute instants (real time).
 *
 * @param type       boost type id
 * @param multiplier multiplier, at least 2
 * @param startedAt  start instant, epoch milliseconds (UTC)
 * @param expiresAt  expiry instant, epoch milliseconds (UTC)
 */
public record GlobalBoost(String type, int multiplier, long startedAt, long expiresAt) {

    public boolean isExpired(long now) {
        return expiresAt <= now;
    }

    /** Bossbar progress: remaining / total, clamped to [0, 1]. */
    public float progress(long now) {
        return MultiplierCalculator.progress(expiresAt - now, expiresAt - startedAt);
    }
}
