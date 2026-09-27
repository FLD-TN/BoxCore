package com.dex.boxcore.item;

/**
 * Boost data carried by a bottle's PersistentDataContainer (§6.2).
 *
 * @param type            type id
 * @param multiplier      multiplier
 * @param durationSeconds duration in seconds
 */
public record BottleData(String type, int multiplier, long durationSeconds) {}
