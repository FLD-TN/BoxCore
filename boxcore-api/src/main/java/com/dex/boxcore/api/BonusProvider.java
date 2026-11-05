package com.dex.boxcore.api;

import java.util.UUID;

/**
 * A flat bonus added on top of a player's multiplier (since api 1.4.0, BoxCore 1.6.0), e.g. the active pet of
 * BoxPet. Registered with {@link BoostService#registerBonusProvider}.
 *
 * <p>The effective multiplier becomes {@code min(max(R + B, G, C, x1) + P, max-multiplier)}, where {@code P} is
 * the value returned here. Unlike the global and clan boosts, the bonus <b>adds</b> to whatever source wins.
 * For a type declared with {@code decimal: false} only the whole part of the final value is kept, so a bonus below
 * x1 has no effect there.
 *
 * <p>BoxCore calls {@link #bonusTenths} on the main thread when it recomputes a player (join, boost changes,
 * permission refresh, {@link BoostService#refreshBonusOf}). It must be fast and must not throw; an exception
 * counts as 0 and is reported once.
 */
@FunctionalInterface
public interface BonusProvider {

    /**
     * @param player an online player
     * @param type   a declared boost type id
     * @return the bonus in tenths ({@code 5} = +x0.5), {@code 0} for none; negative values count as 0
     */
    int bonusTenths(UUID player, String type);
}
