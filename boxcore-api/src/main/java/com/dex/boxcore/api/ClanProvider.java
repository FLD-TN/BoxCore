package com.dex.boxcore.api;

import java.util.Collection;
import java.util.UUID;

/**
 * Tells BoxCore which clan a player belongs to, for clan boosts (Update 05 E3). Implemented by the clan plugin and
 * registered with {@link BoostService#registerClanProvider}; BoxCore never depends on that plugin.
 *
 * <p>Every method is called on the main thread and must be cheap (it runs when a player's multipliers are
 * recomputed). A method that throws is treated as "no clan".
 *
 * @since 1.3.0
 */
public interface ClanProvider {

    /** The player's clan, {@code null} if none. */
    UUID clanOf(UUID player);

    /** True if the player may use a clan bottle for their clan (Minh Chủ or Phó Bang). */
    boolean canUseClanBoost(UUID player);

    /** The clan's coloured name as legacy text ({@code §} codes), shown in messages. */
    String clanName(UUID clan);

    /** The clan's members that are online now. */
    Collection<UUID> onlineMembers(UUID clan);
}
