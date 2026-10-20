package com.dex.boxcore.util;

import java.util.ArrayList;
import java.util.List;

/** Word wrapping of plain text for item lore. Unit-tested. */
public final class TextWrap {

    private TextWrap() {
    }

    /**
     * Splits {@code text} into lines of at most {@code width} characters, breaking between words only; a word
     * longer than {@code width} gets a line of its own. A {@code width} below 1 disables wrapping.
     *
     * @return at least one line ({@code ""} for blank text)
     */
    public static List<String> wrap(String text, int width) {
        String trimmed = text == null ? "" : text.trim();
        if (width < 1 || trimmed.length() <= width) return List.of(trimmed);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : trimmed.split("\\s+")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());
        return List.copyOf(lines);
    }
}
