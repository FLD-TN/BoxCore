package com.dex.boxcore.api;

/** Result of {@link GemService#deposit} and {@link GemService#withdraw}. */
public enum GemResult {
    SUCCESS,
    /** withdraw: balance lower than the amount. */
    INSUFFICIENT_FUNDS,
    /** deposit: the new balance would exceed the maximum. */
    BALANCE_LIMIT,
    /** The player is offline, not loaded yet, or gem storage is disabled. */
    NOT_LOADED,
    /** {@code amount < 1}, or the reason is null or blank. */
    INVALID_AMOUNT
}
