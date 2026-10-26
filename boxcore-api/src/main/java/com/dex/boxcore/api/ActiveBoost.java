package com.dex.boxcore.api;

/**
 * A running boost (Update 05 E3).
 *
 * @param type      boost type id
 * @param tenths    multiplier in tenths ({@code 20} = x2)
 * @param startedAt start instant, epoch milliseconds; an extension keeps it and only moves {@code expiresAt}
 * @param expiresAt expiry instant, epoch milliseconds
 * @since 1.3.0
 */
public record ActiveBoost(String type, int tenths, long startedAt, long expiresAt) {

    /** Whole seconds left at {@code now}, at least 0. */
    public long remainingSeconds(long now) {
        return Math.max(0, (expiresAt - now + 999) / 1000);
    }
}
