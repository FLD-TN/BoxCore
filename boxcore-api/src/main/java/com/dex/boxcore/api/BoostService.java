package com.dex.boxcore.api;

import java.util.UUID;

/**
 * BoxCore boost service.
 *
 * <p>Resolve it with {@code Bukkit.getServicesManager().load(BoostService.class)} after checking
 * {@code Bukkit.getPluginManager().isPluginEnabled("BoxCore")}.
 *
 * <p>The effective multiplier of a player for a type combines the player's rank permission, active personal
 * bottle and the server-wide (global) boost according to the rules of the installed BoxCore version
 * (1.1.0: {@code max(rank + bottle, global)}, capped per type). It is at least x1 ("no boost").
 *
 * <p>Since 1.4.0 a type declared with {@code decimal: true} in BoxCore's config (by default only {@code quest})
 * may have one decimal, e.g. x1.5. Read it with {@link #getMultiplierTenths} ({@code 15}) and apply it with the
 * helpers of {@link Multipliers}; {@link #getMultiplier} returns only the whole part. Types declared with
 * {@code decimal: false} (by default {@code mining}) are always whole, so {@link #getMultiplier} is exact for them.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>No method ever throws, for any input. A {@code null} player id or type yields {@code 1}.</li>
 *   <li>The value can change between calls (boosts start and expire, permissions are refreshed).
 *       Call it at the moment you grant something and do <b>not</b> cache the result.</li>
 *   <li>The API is versioned with the plugin. Later versions may only add methods with default
 *       implementations; existing signatures never change.</li>
 * </ul>
 */
public interface BoostService {

    /**
     * Whole part of the effective multiplier (always >= 1) of an ONLINE player for a boost type: x1.5 → 1,
     * x2.5 → 2. Exact for types declared with {@code decimal: false}; use {@link #getMultiplierTenths} otherwise.
     * Returns 1 if the player is offline or the type is not declared.
     * Thread-safe, O(1), allocation-free: reads a precomputed cache.
     *
     * @param playerId the player's UUID; {@code null} returns 1
     * @param type     the boost type id, e.g. {@link BoostTypes#MINING}; {@code null} returns 1
     * @return the effective multiplier, never less than 1
     */
    int getMultiplier(UUID playerId, String type);

    /**
     * Effective multiplier in tenths ({@code 15} = x1.5), always {@code >= 10}. Same guarantees as
     * {@link #getMultiplier}; always a multiple of 10 for a type declared with {@code decimal: false}.
     * Returns 10 if the player is offline or the type is not declared.
     *
     * @param playerId the player's UUID; {@code null} returns 10
     * @param type     the boost type id; {@code null} returns 10
     * @return the effective multiplier in tenths, never less than 10
     * @since 1.2.0
     */
    default int getMultiplierTenths(UUID playerId, String type) {
        return getMultiplier(playerId, type) * Multipliers.ONE;
    }

    /**
     * Effective multiplier as a double ({@code 1.5}), always {@code >= 1}.
     *
     * @since 1.2.0
     */
    default double getMultiplierExact(UUID playerId, String type) {
        return getMultiplierTenths(playerId, type) / 10.0;
    }

    /**
     * True if the type is declared in BoxCore's config.
     *
     * @param type the boost type id; {@code null} returns false
     * @return whether the type is declared
     */
    boolean isType(String type);

    /**
     * Convenience: {@code floor(amount * multiplier)} with the exact multiplier ({@link #getMultiplierTenths}),
     * computed in long, saturating at Long.MAX_VALUE instead of overflowing. amount <= 0 is returned unchanged.
     *
     * @param playerId the player's UUID
     * @param type     the boost type id
     * @param amount   the base amount
     * @return the multiplied amount
     */
    default long multiply(UUID playerId, String type, long amount) {
        return Multipliers.floor(amount, getMultiplierTenths(playerId, type));
    }
}
