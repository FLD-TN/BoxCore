package com.dex.boxcore.config;

import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.gem.GemConfig;
import com.dex.boxcore.util.DurationFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * Immutable snapshot of {@code config.yml}. Parsed into a new object on every load; invalid values
 * fall back to defaults with a warning naming file, key and value (§11).
 */
public final class CoreConfig {

    public enum UseClick { RIGHT, LEFT }

    public static final String FILE = "config.yml";
    private static final Pattern TYPE_ID = Pattern.compile("^[a-z0-9_]{1,32}$");

    public static final Map<String, String> DEFAULT_PALETTE;
    static {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("text", "#FFFFFF");
        p.put("muted", "#C8C8C8");
        p.put("accent", "#FFD166");
        p.put("success", "#55FF55");
        p.put("error", "#FF5555");
        DEFAULT_PALETTE = Collections.unmodifiableMap(p);
    }

    private final Map<String, BoostType> types;
    private final List<BoostType> typeList;
    private final Material bottleMaterial;
    private final UseClick useClick;
    private final int useCooldownTicks;
    private final int confirmWindowSeconds;
    private final Key successSound;
    private final int descriptionWidth;
    private final int permissionRefreshSeconds;
    private final int saveDebounceSeconds;
    private final ZoneId timezone;
    private final boolean smallCaps;
    private final Map<String, TextColor> palette;
    private final char decimalSeparator;
    private final GemConfig gem;

    private CoreConfig(Map<String, BoostType> types, Material bottleMaterial, UseClick useClick,
                       int useCooldownTicks, int confirmWindowSeconds, Key successSound, int descriptionWidth,
                       int permissionRefreshSeconds,
                       int saveDebounceSeconds, ZoneId timezone, boolean smallCaps, Map<String, TextColor> palette,
                       char decimalSeparator, GemConfig gem) {
        this.types = Collections.unmodifiableMap(types);
        this.typeList = List.copyOf(types.values());
        this.bottleMaterial = bottleMaterial;
        this.useClick = useClick;
        this.useCooldownTicks = useCooldownTicks;
        this.confirmWindowSeconds = confirmWindowSeconds;
        this.successSound = successSound;
        this.descriptionWidth = descriptionWidth;
        this.permissionRefreshSeconds = permissionRefreshSeconds;
        this.saveDebounceSeconds = saveDebounceSeconds;
        this.timezone = timezone;
        this.smallCaps = smallCaps;
        this.palette = Collections.unmodifiableMap(palette);
        this.decimalSeparator = decimalSeparator;
        this.gem = gem;
    }

    /**
     * Parses {@code config.yml}.
     *
     * @throws IOException                   if the file cannot be read
     * @throws InvalidConfigurationException if the file is not valid YAML
     */
    public static CoreConfig load(File file, Logger logger) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return parse(yaml, logger);
    }

    /** Parses an already loaded YAML document. Never throws for bad values: they fall back to defaults. */
    public static CoreConfig parse(YamlConfiguration yaml, Logger logger) {
        return parse(yaml, logger, m -> m.isItem() && !m.isAir());
    }

    /** @param isUsableItem material check (needs a running server; replaceable in unit tests) */
    public static CoreConfig parse(YamlConfiguration yaml, Logger logger, java.util.function.Predicate<Material> isUsableItem) {
        Warn warn = new Warn(logger);

        // style first: type names and descriptions are styled at load
        boolean smallCaps = bool(yaml, "style.small-caps", false, warn);
        Map<String, TextColor> palette = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : DEFAULT_PALETTE.entrySet()) {
            String key = "style.palette." + e.getKey();
            String raw = yaml.getString(key);
            TextColor color = raw == null ? null : TextColor.fromHexString(raw.trim());
            if (color == null) {
                if (raw != null) warn.invalid(key, raw, e.getValue());
                color = TextColor.fromHexString(e.getValue());
            }
            palette.put(e.getKey(), color);
        }

        Map<String, BoostType> types = new LinkedHashMap<>();
        ConfigurationSection typesSection = yaml.getConfigurationSection("types");
        if (typesSection == null) {
            logger.warning("[" + FILE + "] 'types' section is missing, no boost type is declared");
        } else {
            for (String id : typesSection.getKeys(false)) {
                BoostType type = parseType(typesSection, id, types.size(), warn, logger);
                if (type != null) types.put(id, type);
            }
        }

        Material material = Material.EXPERIENCE_BOTTLE;
        String rawMaterial = yaml.getString("bottle.material");
        if (rawMaterial != null) {
            Material m = Material.matchMaterial(rawMaterial.trim());
            if (m == null || !isUsableItem.test(m)) {
                warn.invalid("bottle.material", rawMaterial, material.name());
            } else {
                material = m;
            }
        }

        UseClick useClick = UseClick.RIGHT;
        String rawClick = yaml.getString("bottle.use-click");
        if (rawClick != null) {
            try {
                useClick = UseClick.valueOf(rawClick.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                warn.invalid("bottle.use-click", rawClick, "RIGHT");
            }
        }

        int cooldown = integer(yaml, "bottle.use-cooldown-ticks", 10, 0, 1200, warn);
        int confirmWindow = integer(yaml, "bottle.confirm-window-seconds", 3, 1, 60, warn);
        Key sound = Key.key("minecraft", "entity.player.levelup");
        Object rawSound = yaml.get("bottle.success-sound");
        if (rawSound != null) {
            String text = String.valueOf(rawSound).trim();
            if (text.isEmpty()) {
                sound = null;
            } else {
                try {
                    sound = Key.key(text.toLowerCase(Locale.ROOT));
                } catch (InvalidKeyException ex) {
                    warn.invalid("bottle.success-sound", rawSound, sound.asString());
                }
            }
        }
        int descriptionWidth = integer(yaml, "bottle.description-width", 25, 0, 200, warn);
        int permRefresh = integer(yaml, "permission-refresh-seconds", 30, 5, 3600, warn);
        int debounce = integer(yaml, "storage.save-debounce-seconds", 5, 1, 300, warn);

        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        String rawZone = yaml.getString("timezone");
        if (rawZone != null) {
            try {
                zone = ZoneId.of(rawZone.trim());
            } catch (DateTimeException ex) {
                warn.invalid("timezone", rawZone, zone.getId());
            }
        }

        char separator = '.';
        Object rawSeparator = yaml.get("multiplier-decimal-separator");
        if (rawSeparator != null) {
            String text = String.valueOf(rawSeparator);
            if (text.equals(".") || text.equals(",")) {
                separator = text.charAt(0);
            } else {
                warn.invalid("multiplier-decimal-separator", rawSeparator, "'.'");
            }
        }

        GemConfig gem = GemConfig.parse(yaml, logger);

        return new CoreConfig(types, material, useClick, cooldown, confirmWindow, sound, descriptionWidth, permRefresh,
                debounce,
                zone, smallCaps, palette, separator, gem);
    }

    private static BoostType parseType(ConfigurationSection section, String id, int index,
                                       Warn warn, Logger logger) {
        String path = "types." + id;
        if (!TYPE_ID.matcher(id).matches()) {
            logger.warning("[" + FILE + "] Skipping boost type '" + id + "': id must match ^[a-z0-9_]{1,32}$");
            return null;
        }
        ConfigurationSection s = section.getConfigurationSection(id);
        if (s == null) {
            logger.warning("[" + FILE + "] Skipping boost type '" + id + "': not a section");
            return null;
        }
        boolean decimal = false;
        Object rawDecimal = s.get("decimal");
        if (rawDecimal instanceof Boolean b) {
            decimal = b;
        } else if (rawDecimal != null) {
            warn.invalid(path + ".decimal", rawDecimal, false);
        }
        Object rawMax = s.get("max-multiplier");
        int max = rawMax instanceof Number ? Multipliers.parse(String.valueOf(rawMax)) : -1;
        if (max < BoostType.minTenths(decimal) || max > BoostType.LIMIT_TENTHS
                || (!decimal && max % Multipliers.ONE != 0)) {
            logger.warning("[" + FILE + "] Skipping boost type '" + id + "': " + path + ".max-multiplier must be "
                    + (decimal ? "a number from 1.1 to 10000 with at most one decimal"
                               : "an integer from 2 to 10000 (or set decimal: true)")
                    + " (value: " + rawMax + ")");
            return null;
        }
        String rawDuration = s.getString("max-duration");
        long maxDuration = DurationFormat.parse(rawDuration);
        if (maxDuration <= 0) {
            logger.warning("[" + FILE + "] Skipping boost type '" + id + "': " + path
                    + ".max-duration must be a duration like 24h (value: " + rawDuration + ")");
            return null;
        }
        String displayName = s.getString("display-name");
        if (displayName == null || displayName.isBlank()) {
            if (displayName != null || s.contains("display-name")) warn.invalid(path + ".display-name", displayName, id);
            displayName = id;
        }
        String description = s.getString("description", "");
        TextColor textColor = null;
        String rawTextColor = s.getString("color");
        if (rawTextColor != null) {
            textColor = TextColor.fromHexString(rawTextColor.trim());
            if (textColor == null) warn.invalid(path + ".color", rawTextColor, "palette text colour");
        }
        BossBar.Color color = BossBar.Color.WHITE;
        String rawColor = s.getString("bossbar-color");
        if (rawColor != null) {
            try {
                color = BossBar.Color.valueOf(rawColor.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                warn.invalid(path + ".bossbar-color", rawColor, "WHITE");
            }
        }
        return new BoostType(id, index, displayName, description, max, decimal, maxDuration, textColor, color);
    }

    private static boolean bool(YamlConfiguration yaml, String key, boolean def, Warn warn) {
        Object raw = yaml.get(key);
        if (raw == null) return def;
        if (raw instanceof Boolean b) return b;
        warn.invalid(key, raw, def);
        return def;
    }

    private static int integer(YamlConfiguration yaml, String key, int def, int min, int max, Warn warn) {
        Object raw = yaml.get(key);
        if (raw == null) return def;
        if (raw instanceof Integer i && i >= min && i <= max) return i;
        warn.invalid(key, raw, def + " (allowed " + min + ".." + max + ")");
        return def;
    }

    private record Warn(Logger logger) {
        void invalid(String key, Object value, Object fallback) {
            logger.warning("[" + FILE + "] Invalid value for '" + key + "': '" + value + "', using default " + fallback);
        }
    }

    /** Declared types in declaration order, keyed by id. Immutable. */
    public Map<String, BoostType> types() { return types; }
    public List<BoostType> typeList() { return typeList; }
    public BoostType type(String id) { return id == null ? null : types.get(id); }
    public Material bottleMaterial() { return bottleMaterial; }
    public UseClick useClick() { return useClick; }
    public int useCooldownTicks() { return useCooldownTicks; }
    public int confirmWindowSeconds() { return confirmWindowSeconds; }
    /** Sound played on a successful activation, {@code null} if disabled. */
    public Key successSound() { return successSound; }
    /** Characters per lore line for the type description, 0 = no wrapping. */
    public int descriptionWidth() { return descriptionWidth; }
    public int permissionRefreshSeconds() { return permissionRefreshSeconds; }
    public int saveDebounceSeconds() { return saveDebounceSeconds; }
    /** The {@code gem} section (Update 03). */
    public GemConfig gem() { return gem; }
    public ZoneId timezone() { return timezone; }
    public boolean smallCaps() { return smallCaps; }
    public Map<String, TextColor> palette() { return palette; }
    /** Decimal separator of displayed multipliers ({@code multiplier-decimal-separator}), {@code '.'} or {@code ','}. */
    public char decimalSeparator() { return decimalSeparator; }
    /** A multiplier in tenths formatted for display: {@code 15} → {@code 1.5}. */
    public String formatMultiplier(int tenths) { return Multipliers.format(tenths, decimalSeparator); }
}
