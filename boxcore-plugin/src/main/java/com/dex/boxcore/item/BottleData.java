package com.dex.boxcore.item;

/**
 * Boost data carried by a bottle's PersistentDataContainer (§6.2).
 *
 * @param type            type id
 * @param tenths          multiplier in tenths (Update 04 D1), from {@code boost_multiplier_tenths} or
 *                        {@code boost_multiplier x 10}
 * @param durationSeconds duration in seconds
 * @param clan            true for a clan bottle ({@code boost_scope = "clan"}, Update 05 E3.4)
 */
public record BottleData(String type, int tenths, long durationSeconds, boolean clan) {

    /** A personal bottle. */
    public BottleData(String type, int tenths, long durationSeconds) {
        this(type, tenths, durationSeconds, false);
    }
}
