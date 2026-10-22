package com.dex.boxcore.boost;

import com.dex.boxcore.api.Multipliers;
import java.util.List;

/**
 * Pure multiplier rules (Update 01, U1), in tenths (Update 04 D1: {@code 15} = x1.5, {@code 10} = x1). Per player
 * and type, with absent sources counted as 0:
 *
 * <pre>
 * personal  = max(R + B, 10)                    rank + active personal bottle
 * effective = min(max(personal, G, 10), maxTenths)
 * </pre>
 *
 * The global boost {@code G} never adds up with rank or bottle; the cap applies to the final value.
 */
public final class MultiplierCalculator {

    private MultiplierCalculator() {}

    /** {@code max(R + B, 10)}; {@code rank} and {@code bottle} are 0 when absent. */
    public static int personal(int rank, int bottle) {
        return Math.max(Math.max(rank, 0) + Math.max(bottle, 0), Multipliers.ONE);
    }

    /**
     * Effective multiplier of one player for one type, in tenths.
     *
     * @param rank      rank value R (0 if none)
     * @param bottle    active personal bottle B (0 if none)
     * @param global    active global boost G (0 if none)
     * @param maxTenths the type's cap
     */
    public static int effective(int rank, int bottle, int global, int maxTenths) {
        int value = Math.max(Math.max(personal(rank, bottle), global), Multipliers.ONE);
        return Math.max(Multipliers.ONE, Math.min(value, maxTenths));
    }

    /**
     * Update 01 U2: a bottle {@code m} is useful (case A) when the value it would give as the player's only bottle
     * of the type is higher than the current value, and it is higher than the active bottle.
     *
     * @param active the active bottle's multiplier (0 if none)
     */
    public static boolean isUseful(int rank, int active, int global, int m, int maxTenths) {
        int current = effective(rank, active, global, maxTenths);
        int after = effective(rank, m, global, maxTenths);
        return after > current && m > active;
    }

    /**
     * A bottle with the same multiplier as the active bottle renews it: the active bottle is deleted (its remaining
     * time is lost) and the new one starts with its full duration, whatever the rank or global values.
     *
     * @param active the active bottle's multiplier (0 if none)
     */
    public static boolean isRenewal(int active, int m) {
        return active > 0 && m == active;
    }

    /** True when the global boost alone determines the value ({@code G > personal}). */
    public static boolean globalWins(int rank, int bottle, int global) {
        return global > personal(rank, bottle);
    }

    /** Multiplier of an active global boost, 0 if none or expired. */
    public static int global(GlobalBoost global, long now) {
        return global == null || global.isExpired(now) ? 0 : global.tenths();
    }

    /** Multiplier of the player's active bottle of the type (the highest if legacy data holds several), 0 if none. */
    public static int bottle(List<PersonalBoost> personal, String type, long now) {
        PersonalBoost b = displayed(personal, type, now);
        return b == null ? 0 : b.tenths();
    }

    /**
     * The player's active bottle of the type: highest multiplier, tie broken by more remaining time.
     *
     * @return the boost, or {@code null} if the player has no active personal boost of the type
     */
    public static PersonalBoost displayed(List<PersonalBoost> personal, String type, long now) {
        PersonalBoost best = null;
        if (personal == null) return null;
        for (int i = 0, n = personal.size(); i < n; i++) {
            PersonalBoost b = personal.get(i);
            if (!b.type().equals(type) || b.isExpired(now)) continue;
            if (best == null || b.tenths() > best.tenths()
                    || (b.tenths() == best.tenths() && b.expiresAt() > best.expiresAt())) {
                best = b;
            }
        }
        return best;
    }

    /** remaining / total clamped to [0, 1]; 0 when total is not positive. */
    public static float progress(long remainingMs, long totalMs) {
        if (totalMs <= 0 || remainingMs <= 0) return 0f;
        if (remainingMs >= totalMs) return 1f;
        return (float) ((double) remainingMs / (double) totalMs);
    }
}
