package com.dex.boxcore.boost;

import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.config.BoostType;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Map;

/**
 * Rank permission values R (§3.2, Update 01 U1, Update 04 D4), in tenths: {@code boxcore.boost.<type>.<n>} with
 * {@code n} a whole number ({@code boxcore.boost.quest.3} = x3) or, for a type with {@code decimal: true}, a whole
 * number, {@code _} and one digit ({@code boxcore.boost.quest.1_5} = x1.5). The largest valid value above x1
 * granted with value {@code true} wins, clamped to the type's max multiplier; {@code 0} when the player has no
 * such permission (x1 counts as no rank). A decimal value for a whole-number type ({@code mining.1_5}) is
 * ignored. Computed in one pass over the effective permissions, never by probing {@code hasPermission}.
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
     * @return values in tenths indexed by {@link BoostType#index()}, 0 (no rank) or above 10
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
        int n = parseTenths(node, dot + 1);
        if (n <= Multipliers.ONE || type.refusesDecimal(n)) return; // invalid, x1 (no rank), or 1_5 for a whole type
        n = Math.min(n, type.maxTenths());
        if (n > values[type.index()]) values[type.index()] = n;
    }

    /**
     * Parses {@code <digits>} or {@code <digits>_<digit>} from {@code start} to the end into tenths; -1 if neither.
     * Saturates at {@link BoostType#LIMIT_TENTHS} (clamped to the type's cap afterwards).
     */
    static int parseTenths(String s, int start) {
        int end = s.length();
        int underscore = s.indexOf('_', start);
        int wholeEnd = underscore < 0 ? end : underscore;
        if (wholeEnd == start) return -1;
        long value = 0;
        for (int i = start; i < wholeEnd; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = Math.min(value * 10 + (c - '0'), BoostType.LIMIT_TENTHS);
        }
        value *= Multipliers.ONE;
        if (underscore >= 0) {
            if (underscore != end - 2) return -1; // exactly one digit after '_'
            char d = s.charAt(end - 1);
            if (d < '0' || d > '9') return -1;
            value += d - '0';
        }
        return (int) Math.min(value, BoostType.LIMIT_TENTHS);
    }
}
