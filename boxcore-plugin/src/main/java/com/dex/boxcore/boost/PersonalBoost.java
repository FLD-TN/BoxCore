package com.dex.boxcore.boost;

/**
 * A personal boost started by using a bottle. Stored as an absolute expiry instant (real time).
 *
 * @param type         boost type id
 * @param tenths       multiplier in tenths (Update 04 D1), at least the type's minimum
 * @param expiresAt    expiry instant, epoch milliseconds (UTC)
 * @param totalSeconds original duration, used for bossbar progress
 */
public record PersonalBoost(String type, int tenths, long expiresAt, long totalSeconds) {

    public boolean isExpired(long now) {
        return expiresAt <= now;
    }

    /** Bossbar progress: remaining / total, clamped to [0, 1]. */
    public float progress(long now) {
        return MultiplierCalculator.progress(expiresAt - now, totalSeconds * 1000L);
    }
}
