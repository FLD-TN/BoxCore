package com.dex.boxcore.api;

/**
 * Outcome of {@link BoostService#startClanBoost} (Update 05 E3.3).
 *
 * @param status      what happened, or why nothing happened
 * @param oldTenths   multiplier of the clan's boost of that type before the call, 0 if none
 * @param oldExpiresAt expiry of that boost before the call, 0 if none
 * @param expiresAt   expiry after the call (unchanged or 0 when nothing happened)
 * @param lostSeconds seconds dropped because of {@code max-duration} (extension) or by the replaced boost
 * @since 1.3.0
 */
public record ClanBoostResult(Status status, int oldTenths, long oldExpiresAt, long expiresAt, long lostSeconds) {

    public enum Status {
        /** No boost of the type was running: started. */
        STARTED,
        /** Same multiplier: the duration was added (maybe cut at {@code max-duration}, see {@code lostSeconds}). */
        EXTENDED,
        /** Higher multiplier: replaced the running boost, its remaining time is lost. */
        REPLACED,
        /** Higher multiplier, not confirmed: nothing changed; call again with {@code confirmed = true}. */
        CONFIRM_REPLACE,
        /** Same multiplier but the duration would be cut at {@code max-duration}, not confirmed: nothing changed. */
        CONFIRM_CAPPED,
        /** Lower multiplier than the running boost: refused. */
        LOWER,
        /** Same multiplier and already at {@code max-duration}: refused. */
        FULL,
        /** Unknown type, or multiplier / duration outside the type's limits. */
        INVALID,
        /** No clan provider is registered, the clan id is null, or the call was not on the main thread. */
        UNAVAILABLE;

        /** True when the clan's boost changed. */
        public boolean applied() {
            return this == STARTED || this == EXTENDED || this == REPLACED;
        }
    }

    public static ClanBoostResult of(Status status) {
        return new ClanBoostResult(status, 0, 0, 0, 0);
    }
}
