package com.dex.boxcore.gem;

import java.util.Locale;

/** Pure helpers for amounts and reasons (Update 03 §4.3, §5.1, §6). Unit-tested. */
public final class GemFormat {

    /** Longest accepted amount text: 13 digits covers the 10^12 hard cap. */
    private static final int MAX_DIGITS = 13;
    public static final int MAX_REASON_LENGTH = 64;

    private static final long[] UNITS = {1_000_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L};
    private static final char[] SUFFIXES = {'T', 'B', 'M', 'K'};

    private GemFormat() {}

    /** {@code 1250} → {@code 1.250}; grouping with {@code .} as in BoxQuest. */
    public static String grouped(long amount) {
        if (amount < 0) return amount == Long.MIN_VALUE ? Long.toString(amount) : "-" + grouped(-amount);
        String digits = Long.toString(amount);
        int len = digits.length();
        if (len <= 3) return digits;
        StringBuilder out = new StringBuilder(len + len / 3);
        int first = len % 3 == 0 ? 3 : len % 3;
        out.append(digits, 0, first);
        for (int i = first; i < len; i += 3) out.append('.').append(digits, i, i + 3);
        return out.toString();
    }

    /** {@code 999}, {@code 1,2K}, {@code 3,4M}, {@code 5,6B}, {@code 1,0T}: one decimal, rounded down. */
    public static String shortForm(long amount) {
        if (amount < 0) return amount == Long.MIN_VALUE ? Long.toString(amount) : "-" + shortForm(-amount);
        for (int i = 0; i < UNITS.length; i++) {
            long unit = UNITS[i];
            if (amount >= unit) {
                long tenths = amount / (unit / 10);
                return (tenths / 10) + "," + (tenths % 10) + SUFFIXES[i];
            }
        }
        return Long.toString(amount);
    }

    /** Strict non-negative integer: digits only, no sign, separators or suffixes; -1 if invalid. */
    public static long parseAmount(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_DIGITS) return -1;
        long value = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value;
    }

    /**
     * Normalises a reason to {@code [a-z0-9_:./-]}, at most 64 characters: lower-cased, other characters
     * replaced by {@code _}, truncated. {@code null} if the input is null or blank.
     */
    public static String sanitizeReason(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        int len = Math.min(lower.length(), MAX_REASON_LENGTH);
        StringBuilder out = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == ':' || c == '.' || c == '/' || c == '-';
            out.append(ok ? c : '_');
        }
        return out.toString();
    }

    /** {@code +50} / {@code -20} / {@code 0} for transaction listings. */
    public static String signed(long delta) {
        return delta > 0 ? "+" + grouped(delta) : grouped(delta);
    }
}
