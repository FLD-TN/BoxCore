package com.dex.boxcore.api;

/**
 * The data of a boost bottle, as read from the item (Update 05 E3.4). The values are not validated against the
 * current config: {@link BoostService#startClanBoost} does that.
 *
 * @param type            boost type id
 * @param tenths          multiplier in tenths
 * @param durationSeconds duration of the bottle
 * @param clan            true for a clan bottle, false for a personal bottle
 * @since 1.3.0
 */
public record BottleInfo(String type, int tenths, long durationSeconds, boolean clan) {}
