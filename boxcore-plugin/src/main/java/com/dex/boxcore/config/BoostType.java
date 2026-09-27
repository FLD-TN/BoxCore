package com.dex.boxcore.config;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;

/**
 * A boost type declared in {@code config.yml}.
 *
 * @param id                 type id, {@code ^[a-z0-9_]{1,32}$}
 * @param index              position in declaration order, used to index effective-multiplier arrays
 * @param displayName        display name, already styled (small caps applied if enabled)
 * @param description        description template (may contain {@code %multiplier%}), already styled
 * @param maxMultiplier      largest allowed multiplier, at least 2
 * @param maxDurationSeconds largest allowed duration in seconds, at least 1
 * @param textColor          text colour of the {@code <type>} message tag, {@code null} = palette {@code text}
 * @param color              bossbar colour (only the 7 Minecraft bar colours exist)
 */
public record BoostType(String id, int index, String displayName, String description,
                        int maxMultiplier, long maxDurationSeconds, TextColor textColor, BossBar.Color color) {

    /** True if {@code m} is a valid multiplier for gives and global starts: {@code [2, maxMultiplier]}. */
    public boolean isValidMultiplier(long m) {
        return m >= 2 && m <= maxMultiplier;
    }

    /** True if {@code seconds} is a valid duration: {@code (0, maxDuration]}. */
    public boolean isValidDuration(long seconds) {
        return seconds > 0 && seconds <= maxDurationSeconds;
    }

    /** The description with {@code %multiplier%} resolved. */
    public String description(int multiplier) {
        return description.replace("%multiplier%", Integer.toString(multiplier));
    }
}
