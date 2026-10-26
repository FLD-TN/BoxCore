package com.dex.boxcore.boost;

import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Effective multipliers of online players (§7.1), in tenths (Update 04 D1). Entries are immutable snapshots that are replaced, never
 * mutated, so any thread may read them. Writes happen on the main thread only.
 */
public final class EffectiveCache {

    /**
     * Immutable per-player snapshot; all arrays are indexed by {@link BoostType#index()}.
     *
     * @param types     the declared types this snapshot was computed with (index source)
     * @param effective effective multiplier (always >= 10)
     * @param rank      rank value R (0 = no rank)
     * @param bottle    active personal bottle B (0 = none)
     * @param global    active global boost G (0 = none)
     * @param clanBoost active boost C of the player's clan (0 = none, Update 05 E3)
     * @param clanExpiresAt expiry of that clan boost, epoch milliseconds (0 = none)
     * @param clan      the player's clan when computed, {@code null} if none or no clan plugin
     */
    public record Snapshot(Map<String, BoostType> types, int[] effective, int[] rank, int[] bottle, int[] global,
                           int[] clanBoost, long[] clanExpiresAt, UUID clan) {

        /** Snapshot without clan data (the clan arrays hold zeros). */
        public Snapshot(Map<String, BoostType> types, int[] effective, int[] rank, int[] bottle, int[] global) {
            this(types, effective, rank, bottle, global, new int[effective.length], new long[effective.length], null);
        }

        private int value(int[] values, String type, int absent) {
            BoostType t = type == null ? null : types.get(type);
            return t == null ? absent : values[t.index()];
        }

        public int effective(String type) {
            return value(effective, type, Multipliers.ONE);
        }

        public int rank(String type) {
            return value(rank, type, 0);
        }

        public int bottle(String type) {
            return value(bottle, type, 0);
        }

        public int global(String type) {
            return value(global, type, 0);
        }

        /** The clan boost C of the type, 0 if none. */
        public int clan(String type) {
            return value(clanBoost, type, 0);
        }

        /** Expiry of the clan boost of the type, 0 if none. */
        public long clanExpiresAt(String type) {
            BoostType t = type == null ? null : types.get(type);
            return t == null ? 0L : clanExpiresAt[t.index()];
        }

        /** {@code max(R + B, 10)}. */
        public int personal(String type) {
            return MultiplierCalculator.personal(rank(type), bottle(type));
        }
    }

    private final ConcurrentHashMap<UUID, Snapshot> entries = new ConcurrentHashMap<>();

    /** Effective multiplier in tenths, 10 for unknown players or types. Thread-safe, allocation-free. */
    public int get(UUID player, String type) {
        Snapshot s = entries.get(player);
        return s == null ? Multipliers.ONE : s.effective(type);
    }

    /** @return the player's snapshot, or {@code null} if the player is not online */
    public Snapshot snapshot(UUID player) {
        return entries.get(player);
    }

    /** Recomputes and replaces a player's entry (main thread). */
    public Snapshot recompute(Player player, CoreConfig config, BoostRegistry registry, ClanLink clans, long now) {
        Map<String, BoostType> types = config.types();
        int size = types.size();
        int[] rank = PermissionReader.read(player, types);
        int[] bottle = new int[size];
        int[] global = new int[size];
        int[] clanBoost = new int[size];
        long[] clanExpiresAt = new long[size];
        int[] effective = new int[size];
        List<PersonalBoost> personal = registry.personal(player.getUniqueId());
        UUID clan = clans == null ? null : clans.clanOf(player.getUniqueId());
        for (BoostType type : config.typeList()) {
            int i = type.index();
            bottle[i] = MultiplierCalculator.bottle(personal, type.id(), now);
            global[i] = MultiplierCalculator.global(registry.global(type.id()), now);
            ClanBoost cb = registry.clan(clan, type.id());
            clanBoost[i] = MultiplierCalculator.clan(cb, now);
            clanExpiresAt[i] = clanBoost[i] > 0 ? cb.expiresAt() : 0L;
            effective[i] = MultiplierCalculator.effective(rank[i], bottle[i], global[i], clanBoost[i], type.maxTenths());
            // Update 04 D1: a whole-number type never reports a decimal, even from a boost stored while the
            // type was decimal (the owner switched decimal off); the decimal part is dropped
            if (!type.decimal()) effective[i] -= effective[i] % Multipliers.ONE;
        }
        Snapshot snapshot = new Snapshot(types, effective, rank, bottle, global, clanBoost, clanExpiresAt, clan);
        entries.put(player.getUniqueId(), snapshot);
        return snapshot;
    }

    public void remove(UUID player) {
        entries.remove(player);
    }

    public void clear() {
        entries.clear();
    }
}
