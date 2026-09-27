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
 * (1.1.0: {@code max(rank + bottle, global)}, capped per type). It is always an integer {@code >= 1};
 * {@code 1} means "no boost".
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
     * Effective multiplier (always >= 1) of an ONLINE player for a boost type.
     * Returns 1 if the player is offline or the type is not declared.
     * Thread-safe, O(1), allocation-free: reads a precomputed cache.
     *
     * @param playerId the player's UUID; {@code null} returns 1
     * @param type     the boost type id, e.g. {@link BoostTypes#MINING}; {@code null} returns 1
     * @return the effective multiplier, never less than 1
     */
    int getMultiplier(UUID playerId, String type);

    /**
     * True if the type is declared in BoxCore's config.
     *
     * @param type the boost type id; {@code null} returns false
     * @return whether the type is declared
     */
    boolean isType(String type);

    /**
     * Convenience: amount * getMultiplier(playerId, type), computed in long,
     * saturating at Long.MAX_VALUE instead of overflowing. amount <= 0 is returned unchanged.
     *
     * @param playerId the player's UUID
     * @param type     the boost type id
     * @param amount   the base amount
     * @return the multiplied amount
     */
    default long multiply(UUID playerId, String type, long amount) {
        if (amount <= 0) return amount;
        int m = getMultiplier(playerId, type);
        return m <= 1 ? amount : (amount > Long.MAX_VALUE / m ? Long.MAX_VALUE : amount * m);
    }
}
