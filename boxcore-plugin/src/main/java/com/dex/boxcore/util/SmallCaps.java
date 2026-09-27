package com.dex.boxcore.util;

/**
 * Converts plugin-authored templates to small caps (server-wide text style).
 *
 * <p>Only ASCII letters are converted (upper case is lowered first). Letters with diacritics, digits
 * and punctuation are unchanged. MiniMessage tags ({@code <accent>}, {@code </text>}, ...) and
 * placeholders ({@code %multiplier%}) are copied verbatim, so templates are converted once at load
 * and placeholders are filled afterwards.
 */
public final class SmallCaps {

    private static final char[] MAP = {
            'ᴀ', 'ʙ', 'ᴄ', 'ᴅ', 'ᴇ', 'ꜰ', 'ɢ', 'ʜ', 'ɪ', 'ᴊ', 'ᴋ', 'ʟ', 'ᴍ',
            'ɴ', 'ᴏ', 'ᴘ', 'ǫ', 'ʀ', 'ꜱ', 'ᴛ', 'ᴜ', 'ᴠ', 'ᴡ', 'x', 'ʏ', 'ᴢ'
    };

    private SmallCaps() {}

    /** Converts a single character; non-ASCII-letters are returned unchanged. */
    public static char convert(char c) {
        if (c >= 'A' && c <= 'Z') return MAP[c - 'A'];
        if (c >= 'a' && c <= 'z') return MAP[c - 'a'];
        return c;
    }

    /** Converts a template, skipping MiniMessage tags and {@code %placeholder%} tokens. */
    public static String convert(String template) {
        if (template == null || template.isEmpty()) return template;
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        int n = template.length();
        while (i < n) {
            char c = template.charAt(i);
            if (c == '<') {
                int end = tagEnd(template, i);
                if (end > 0) {
                    out.append(template, i, end + 1);
                    i = end + 1;
                    continue;
                }
            } else if (c == '%') {
                int end = placeholderEnd(template, i);
                if (end > 0) {
                    out.append(template, i, end + 1);
                    i = end + 1;
                    continue;
                }
            } else if (c == '\\' && i + 1 < n && template.charAt(i + 1) == '<') {
                // escaped tag start: keep the escape and the bracket
                out.append(c).append('<');
                i += 2;
                continue;
            }
            out.append(convert(c));
            i++;
        }
        return out.toString();
    }

    /** Index of the closing '>' of a MiniMessage tag starting at {@code start}, or -1. */
    private static int tagEnd(String s, int start) {
        if (start + 1 >= s.length()) return -1;
        char first = s.charAt(start + 1);
        if (!(first == '/' || first == '!' || first == '#' || first == '_' || Character.isLetter(first))) {
            return -1;
        }
        int end = s.indexOf('>', start + 1);
        if (end < 0) return -1;
        // a tag never spans whitespace-separated prose with another '<' inside
        int nextOpen = s.indexOf('<', start + 1);
        if (nextOpen >= 0 && nextOpen < end) return -1;
        return end;
    }

    /** Index of the closing '%' of a placeholder {@code %[a-z0-9_]+%} starting at {@code start}, or -1. */
    private static int placeholderEnd(String s, int start) {
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '%') return i > start + 1 ? i : -1;
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) return -1;
            i++;
        }
        return -1;
    }
}
