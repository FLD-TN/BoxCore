package com.dex.boxcore.api;

/**
 * Multipliers in tenths ({@code 15} = x1.5, {@code 20} = x2), as returned by
 * {@link BoostService#getMultiplierTenths}. Pure static helpers: no method ever throws.
 *
 * <p>Amount helpers return {@code amount} unchanged when it is {@code <= 0} or when {@code tenths <= 10}
 * (no boost), and saturate at {@link Long#MAX_VALUE} instead of overflowing.
 *
 * @since 1.2.0
 */
public final class Multipliers {

    /** x1, no boost. */
    public static final int ONE = 10;

    private Multipliers() {}

    /**
     * Parses {@code "2"}, {@code "2.0"}, {@code "1.5"} or {@code "1,5"} (surrounding spaces ignored) to tenths.
     * At most 5 integer digits and one decimal; no sign, exponent, {@code ".5"} or {@code "5."}.
     * The range is not checked.
     *
     * @return the value in tenths, or {@code -1} if {@code raw} is not such a number
     */
    public static int parse(String raw) {
        if (raw == null) return -1;
        String s = raw.strip();
        int n = s.length();
        int digits = 0;
        int value = 0;
        int i = 0;
        for (; i < n; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            if (++digits > 5) return -1;
            value = value * 10 + (c - '0');
        }
        if (digits == 0) return -1;
        if (i == n) return value * 10;
        char sep = s.charAt(i);
        if ((sep != '.' && sep != ',') || i + 2 != n) return -1;
        char d = s.charAt(i + 1);
        if (d < '0' || d > '9') return -1;
        return value * 10 + (d - '0');
    }

    /** {@code 20} → {@code "2"}, {@code 15} → {@code "1.5"}. Never adds {@code x}. */
    public static String format(int tenths) {
        return format(tenths, '.');
    }

    /** As {@link #format(int)} with the given decimal separator ({@code 15} → {@code "1,5"} for {@code ','}). */
    public static String format(int tenths, char decimalSeparator) {
        int whole = tenths / 10;
        int decimal = Math.abs(tenths % 10);
        if (decimal == 0) return Integer.toString(whole);
        return (tenths < 0 && whole == 0 ? "-0" : Integer.toString(whole)) + decimalSeparator + decimal;
    }

    /** {@code floor(amount * tenths / 10)}. */
    public static long floor(long amount, int tenths) {
        if (amount <= 0 || tenths <= ONE) return amount;
        long product = product(amount, tenths);
        return product == Long.MAX_VALUE ? product : product / 10;
    }

    /** {@code amount * tenths / 10} rounded half up: {@code halfUp(5, 15)} = 8 (7.5), {@code halfUp(3, 15)} = 5 (4.5). */
    public static long halfUp(long amount, int tenths) {
        if (amount <= 0 || tenths <= ONE) return amount;
        long product = product(amount, tenths);
        return product == Long.MAX_VALUE ? product : (product + 5) / 10;
    }

    /** {@code amount * tenths}, or {@link Long#MAX_VALUE} if it would overflow (or not leave room for {@code + 5}). */
    private static long product(long amount, int tenths) {
        return amount > (Long.MAX_VALUE - 5) / tenths ? Long.MAX_VALUE : amount * tenths;
    }
}
