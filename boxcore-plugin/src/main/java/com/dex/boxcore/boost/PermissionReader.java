package com.dex.boxcore.boost;

import com.dex.boxcore.config.BoostType;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Map;

/**
 * Rank permission values R (§3.2, Update 01 U1): {@code boxcore.boost.<type>.<n>}, the largest valid
 * {@code n >= 2} granted with value {@code true}, clamped to the type's max multiplier; {@code 0} when the
 * player has no such permission ({@code n = 1} counts as no rank). Computed in one pass over the effective
 * permissions, never by probing {@code hasPermission} for each {@code n}.
 */
public final class PermissionReader {

    public static final String PREFIX = "boxcore.boost.";

    private PermissionReader() {}

    /** Reads the permission values of an online player (main thread). */
    public static int[] read(Player player, Map<String, BoostType> types) {
        int[] values = newValues(types.size());
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue()) accept(info.getPermission(), types, values);
        }
        return values;
    }

    /**
     * Pure variant for tests.
     *
     * @param grantedNodes permission nodes granted with value {@code true}
     * @return values indexed by {@link BoostType#index()}, 0 (no rank) or at least 2
     */
    public static int[] read(Iterable<String> grantedNodes, Map<String, BoostType> types) {
        int[] values = newValues(types.size());
        for (String node : grantedNodes) accept(node, types, values);
        return values;
    }

    private static int[] newValues(int size) {
        return new int[size]; // 0 = no rank
    }

    private static void accept(String node, Map<String, BoostType> types, int[] values) {
        if (node == null || node.length() <= PREFIX.length() || !node.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return;
        }
        int dot = node.lastIndexOf('.');
        if (dot <= PREFIX.length() || dot == node.length() - 1) return;
        BoostType type = types.get(node.substring(PREFIX.length(), dot).toLowerCase(java.util.Locale.ROOT));
        if (type == null) return;
        int n = parsePositive(node, dot + 1);
        if (n < 2) return; // n = 1 means no rank
        n = Math.min(n, type.maxMultiplier());
        if (n > values[type.index()]) values[type.index()] = n;
    }

    /** Parses digits from {@code start} to the end; -1 if not a plain integer. Saturates at Integer.MAX_VALUE. */
    private static int parsePositive(String s, int start) {
        long value = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = Math.min(value * 10 + (c - '0'), Integer.MAX_VALUE);
        }
        return (int) value;
    }
}
