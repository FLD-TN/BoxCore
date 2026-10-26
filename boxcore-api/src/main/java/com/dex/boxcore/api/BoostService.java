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
 * (1.1.0: {@code max(rank + bottle, global)}, capped per type; 1.5.0 adds the clan boost:
 * {@code max(rank + bottle, global, clan)}). It is at least x1 ("no boost").
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

    // ------------------------------------------------------------------ clan boosts (1.3.0, Update 05 E3)
    // A clan boost applies to every member of the clan: effective = min(max(rank + bottle, global, clan), cap).
    // It counts down in real time, members online or not. The calls below that change state must run on the main
    // thread (others return UNAVAILABLE / do nothing); the defaults are for implementations older than 1.3.0.

    /**
     * Registers the plugin that knows the clans. Replaces a previous provider; removed automatically when
     * {@code owner} is disabled. Every online player's multipliers are recomputed.
     *
     * @since 1.3.0
     */
    default void registerClanProvider(org.bukkit.plugin.Plugin owner, ClanProvider provider) {}

    /**
     * Recomputes a player's multipliers and bossbars after they joined or left a clan. Call it for every online
     * player whose clan changed (join, leave, kick, disband). Safe from any thread (moved to the main thread).
     *
     * @since 1.3.0
     */
    default void refreshClanOf(UUID player) {}

    /**
     * Deletes every boost of a clan (the clan was disbanded), silently. Safe from any thread.
     *
     * @since 1.3.0
     */
    default void clearClan(UUID clan) {}

    /**
     * Starts, extends or replaces a clan's boost with the rules of Update 05 E3.3, and tells the clan's online
     * members. No permission check: the caller decides who may do it. Main thread only.
     *
     * @param clan      the clan
     * @param type      boost type id
     * @param tenths    multiplier in tenths
     * @param seconds   duration in seconds
     * @param confirmed false: a replacement, or an extension cut at max-duration, is not done and answers
     *                  {@code CONFIRM_REPLACE} / {@code CONFIRM_CAPPED}; true: it is done
     * @param actor     name shown to the clan ("Steve"), {@code null} for staff
     * @since 1.3.0
     */
    default ClanBoostResult startClanBoost(UUID clan, String type, int tenths, long seconds, boolean confirmed,
                                           String actor) {
        return ClanBoostResult.of(ClanBoostResult.Status.UNAVAILABLE);
    }

    /**
     * Stops a clan's boost of a type and tells its online members. Main thread only.
     *
     * @return false if none was running
     * @since 1.3.0
     */
    default boolean stopClanBoost(UUID clan, String type) {
        return false;
    }

    /**
     * The clan's running boost of a type. Main thread only.
     *
     * @since 1.3.0
     */
    default java.util.Optional<ActiveBoost> clanBoost(UUID clan, String type) {
        return java.util.Optional.empty();
    }

    /**
     * The boost data of an item, empty if it is not a boost bottle. Reads the item's data only (never its name or
     * lore) and does not validate it against the current config.
     *
     * @since 1.3.0
     */
    default java.util.Optional<BottleInfo> readBottle(org.bukkit.inventory.ItemStack item) {
        return java.util.Optional.empty();
    }
}
