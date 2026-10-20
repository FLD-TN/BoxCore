package com.dex.boxcore.gem;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The {@code gem} section of {@code config.yml} (Update 03 §7). Immutable; invalid values fall back to the
 * defaults with a warning naming the key. A missing section is not an error (servers upgraded from 1.1.0).
 *
 * @param displayName        configured display name, as written (API {@code displayName()})
 * @param color              colour of the currency name in messages
 * @param topExclude         lower-cased player names left out of the leaderboard
 * @param sectionPresent     false when config.yml has no {@code gem} section (defaults used)
 */
public record GemConfig(String displayName, TextColor color, long startingBalance,
                        long maxBalance, long payMinAmount, int payCooldownSeconds, int topSize,
                        int topRefreshSeconds, Set<String> topExclude, String placeholderUnknown,
                        int logKeepDays, boolean sectionPresent) {

    /** Hard cap of any balance: 10^12, so {@code balance + amount} never overflows a long. */
    public static final long HARD_MAX_BALANCE = 1_000_000_000_000L;

    private static final String FILE = "config.yml";
    private static final String DEFAULT_NAME = "Gem";
    private static final String DEFAULT_COLOR = "#8245F8";

    /** Parses the {@code gem} section; never throws for bad values. */
    public static GemConfig parse(YamlConfiguration yaml, Logger logger) {
        boolean present = yaml.isConfigurationSection("gem");
        if (!present) {
            logger.info("config.yml has no 'gem' section, using defaults (see the bundled config for the options)");
        }

        String name = DEFAULT_NAME;
        Object rawName = yaml.get("gem.display-name");
        if (rawName != null) {
            if (rawName instanceof String s && !s.isBlank()) name = s.trim();
            else invalid(logger, "gem.display-name", rawName, DEFAULT_NAME);
        }

        TextColor color = TextColor.fromHexString(DEFAULT_COLOR);
        String rawColor = yaml.getString("gem.color");
        if (rawColor != null) {
            TextColor parsed = TextColor.fromHexString(rawColor.trim());
            if (parsed == null) invalid(logger, "gem.color", rawColor, DEFAULT_COLOR);
            else color = parsed;
        }

        long max = number(yaml, "gem.max-balance", HARD_MAX_BALANCE, 1, HARD_MAX_BALANCE, logger);
        long starting = number(yaml, "gem.starting-balance", 0, 0, max, logger);
        long payMin = number(yaml, "gem.pay.min-amount", 1, 1, max, logger);
        int cooldown = (int) number(yaml, "gem.pay.cooldown-seconds", 3, 0, 3600, logger);
        int topSize = (int) number(yaml, "gem.top.size", 10, 1, 100, logger);
        int topRefresh = (int) number(yaml, "gem.top.refresh-seconds", 60, 10, 86_400, logger);

        Set<String> exclude = new HashSet<>();
        Object rawExclude = yaml.get("gem.top.exclude");
        if (rawExclude != null) {
            if (rawExclude instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null && !String.valueOf(o).isBlank()) exclude.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
                }
            } else {
                invalid(logger, "gem.top.exclude", rawExclude, "[]");
            }
        }

        String unknown = "-";
        Object rawUnknown = yaml.get("gem.placeholder-unknown");
        if (rawUnknown != null) {
            if (rawUnknown instanceof String s) unknown = s;
            else invalid(logger, "gem.placeholder-unknown", rawUnknown, "-");
        }

        int keepDays = (int) number(yaml, "gem.log-keep-days", 180, 0, 36_500, logger);

        return new GemConfig(name, color, starting, max, payMin, cooldown, topSize, topRefresh,
                Set.copyOf(exclude), unknown, keepDays, present);
    }

    private static long number(YamlConfiguration yaml, String key, long def, long min, long max, Logger logger) {
        Object raw = yaml.get(key);
        if (raw == null) return def;
        if ((raw instanceof Integer || raw instanceof Long) && ((Number) raw).longValue() >= min
                && ((Number) raw).longValue() <= max) {
            return ((Number) raw).longValue();
        }
        invalid(logger, key, raw, def + " (allowed " + min + ".." + max + ")");
        return def;
    }

    private static void invalid(Logger logger, String key, Object value, Object fallback) {
        logger.warning("[" + FILE + "] Invalid value for '" + key + "': '" + value + "', using default " + fallback);
    }
}
