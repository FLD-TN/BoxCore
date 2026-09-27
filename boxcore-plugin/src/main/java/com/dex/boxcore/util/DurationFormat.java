package com.dex.boxcore.util;

/**
 * Duration parsing ({@code 30s}, {@code 10m}, {@code 1h30m}, {@code 2d}) and display
 * ({@code 1 giờ 30 phút}, {@code 01:02:03}). All values are in seconds.
 */
public final class DurationFormat {

    /** Upper bound for any parsed duration (100 years), keeps millisecond arithmetic far from overflow. */
    public static final long MAX_SECONDS = 100L * 365 * 24 * 3600;

    private static final long MINUTE = 60;
    private static final long HOUR = 3600;
    private static final long DAY = 86400;

    /** Unit words used for display, e.g. {@code giây}, {@code phút}, {@code giờ}, {@code ngày}. */
    public record Units(String second, String minute, String hour, String day) {
        public static final Units DEFAULT = new Units("giây", "phút", "giờ", "ngày");
    }

    private DurationFormat() {}

    /**
     * Parses one or more {@code <number><unit>} groups with units {@code s m h d}.
     *
     * @return the duration in seconds, or {@code -1} if the input is invalid, zero or above {@link #MAX_SECONDS}
     */
    public static long parse(String input) {
        if (input == null) return -1;
        String s = input.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return -1;
        long total = 0;
        int i = 0;
        int n = s.length();
        while (i < n) {
            int digitsStart = i;
            long number = 0;
            while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                number = number * 10 + (s.charAt(i) - '0');
                if (number > MAX_SECONDS) return -1;
                i++;
            }
            if (i == digitsStart || i >= n) return -1;
            long unit = switch (s.charAt(i)) {
                case 's' -> 1;
                case 'm' -> MINUTE;
                case 'h' -> HOUR;
                case 'd' -> DAY;
                default -> -1;
            };
            if (unit < 0) return -1;
            i++;
            if (number > MAX_SECONDS / unit) return -1;
            total += number * unit;
            if (total > MAX_SECONDS) return -1;
        }
        return total > 0 ? total : -1;
    }

    /**
     * Human display, e.g. {@code 20 phút}, {@code 1 giờ 30 phút}, {@code 2 ngày 3 giờ}, {@code 45 giây}.
     * Zero parts are omitted and at most the two largest non-zero units are shown.
     */
    public static String format(long seconds, Units units) {
        if (seconds <= 0) return "0 " + units.second();
        long[] values = {seconds / DAY, (seconds % DAY) / HOUR, (seconds % HOUR) / MINUTE, seconds % MINUTE};
        String[] words = {units.day(), units.hour(), units.minute(), units.second()};
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < values.length && shown < 2; i++) {
            if (values[i] == 0) continue;
            if (shown > 0) sb.append(' ');
            sb.append(values[i]).append(' ').append(words[i]);
            shown++;
        }
        return sb.toString();
    }

    /** Remaining-time clock: {@code HH:MM:SS} when at least one hour, otherwise {@code MM:SS}. */
    public static String clock(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / HOUR;
        long m = (seconds % HOUR) / MINUTE;
        long s = seconds % MINUTE;
        StringBuilder sb = new StringBuilder(8);
        if (h > 0) {
            if (h < 10) sb.append('0');
            sb.append(h).append(':');
        }
        if (m < 10) sb.append('0');
        sb.append(m).append(':');
        if (s < 10) sb.append('0');
        sb.append(s);
        return sb.toString();
    }

    /** Remaining whole seconds until {@code expiresAt}, rounded up so a bar never shows 00:00 while active. */
    public static long remainingSeconds(long expiresAt, long now) {
        long ms = expiresAt - now;
        if (ms <= 0) return 0;
        return (ms + 999) / 1000;
    }
}
