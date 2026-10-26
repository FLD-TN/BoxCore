package com.dex.boxcore.config;

import com.dex.boxcore.api.Multipliers;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

/**
 * A boost type declared in {@code config.yml}. Multipliers are in tenths (Update 04 D1): {@code 15} = x1.5.
 *
 * @param id                 type id, {@code ^[a-z0-9_]{1,32}$}
 * @param index              position in declaration order, used to index effective-multiplier arrays
 * @param displayName        display name
 * @param description        description template (may contain {@code %multiplier%})
 * @param maxTenths          largest allowed multiplier in tenths, at least {@link #minTenths()}; a multiple of 10
 *                           unless {@code decimal}
 * @param decimal            {@code true}: multipliers may have one decimal (x1.5); {@code false}: whole numbers only
 * @param maxDurationSeconds largest allowed duration in seconds, at least 1
 * @param textColor          text colour of the {@code <type>} message tag, {@code null} = palette {@code text}
 * @param color              bossbar colour (only the 7 Minecraft bar colours exist)
 * @param bottleMaterial     material of this type's bottles ({@code bottle-material}, Update 05 E1), {@code null} =
 *                           the global {@code bottle.material}
 */
public record BoostType(String id, int index, String displayName, String description,
                        int maxTenths, boolean decimal, long maxDurationSeconds, TextColor textColor,
                        BossBar.Color color, Material bottleMaterial) {

    /** A type using the global bottle material. */
    public BoostType(String id, int index, String displayName, String description, int maxTenths, boolean decimal,
                     long maxDurationSeconds, TextColor textColor, BossBar.Color color) {
        this(id, index, displayName, description, maxTenths, decimal, maxDurationSeconds, textColor, color, null);
    }

    /** Largest {@code max-multiplier}: x10000. */
    public static final int LIMIT_TENTHS = 100_000;
    /** Smallest multiplier of a decimal type: x1.1. */
    public static final int MIN_DECIMAL_TENTHS = 11;
    /** Smallest multiplier of a whole-number type: x2. */
    public static final int MIN_WHOLE_TENTHS = 20;

    /** Smallest multiplier for bottles, global starts and ranks: x1.1 (decimal) or x2 (whole). */
    public int minTenths() {
        return minTenths(decimal);
    }

    public static int minTenths(boolean decimal) {
        return decimal ? MIN_DECIMAL_TENTHS : MIN_WHOLE_TENTHS;
    }

    /** True if {@code tenths} has a non-zero decimal and this type does not allow it. */
    public boolean refusesDecimal(long tenths) {
        return !decimal && tenths % Multipliers.ONE != 0;
    }

    /**
     * True if {@code tenths} is a valid multiplier for gives, global starts and bottles:
     * {@code [minTenths, maxTenths]}, whole unless the type is decimal.
     */
    public boolean isValidMultiplier(long tenths) {
        return tenths >= minTenths() && tenths <= maxTenths && !refusesDecimal(tenths);
    }

    /** True if {@code seconds} is a valid duration: {@code (0, maxDuration]}. */
    public boolean isValidDuration(long seconds) {
        return seconds > 0 && seconds <= maxDurationSeconds;
    }

    /** The description with {@code %multiplier%} resolved to the already formatted {@code multiplier}. */
    public String description(String multiplier) {
        return description.replace("%multiplier%", multiplier);
    }
}
