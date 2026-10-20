package com.dex.boxcore.api;

import java.util.UUID;

/**
 * The GEM currency of BoxCore. Obtain it with
 * {@code Bukkit.getServicesManager().load(GemService.class)} when BoxCore is enabled.
 * Only online players whose data is loaded can be read or changed through this API.
 *
 * <p>Rules:
 * <ul>
 *   <li>Amounts and balances are integers ({@code long}). A balance is never negative and never above the
 *       configured maximum.</li>
 *   <li>{@link #withdraw} checks and subtracts in one call: never call {@link #balance} and then decide.</li>
 *   <li>{@link #deposit} of an amount that was just withdrawn successfully in the same tick never fails with
 *       {@link GemResult#BALANCE_LIMIT} or {@link GemResult#INSUFFICIENT_FUNDS}, so it can be used to roll back.</li>
 *   <li>A {@link GemResult#SUCCESS} is final: the change is applied in memory and queued to disk.</li>
 * </ul>
 */
public interface GemService {

    /** Balance of an online, loaded player, or -1 if not loaded (offline, still loading, or storage disabled). Any thread. */
    long balance(UUID playerId);

    /** True if {@link #balance} returns a real value for this player. Any thread. */
    boolean isLoaded(UUID playerId);

    /**
     * Adds {@code amount} (>= 1). Main thread only (throws IllegalStateException otherwise).
     *
     * @param reason short machine-readable origin, e.g. "shopkeeper:tier_shop/t1"; see INTEGRATION.md
     */
    GemResult deposit(UUID playerId, long amount, String reason);

    /**
     * Removes {@code amount} (>= 1) only if the balance is sufficient; all or nothing.
     * Main thread only (throws IllegalStateException otherwise).
     *
     * @param reason short machine-readable origin, e.g. "shopkeeper:tier_shop/t1"; see INTEGRATION.md
     */
    GemResult withdraw(UUID playerId, long amount, String reason);

    /** Configured display name, e.g. "Gem". */
    String displayName();

    /** Formats an amount the way BoxCore displays it, e.g. "1.250". */
    String format(long amount);
}
