package com.dex.boxcore.config;

import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.util.DurationFormat;
import com.dex.boxcore.util.SmallCaps;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Immutable snapshot of {@code messages.yml}: templates (chat in the normal font; small caps, if enabled, only
 * on bottle items and bossbar titles, applied when rendered),
 * palette tags, prefix, and the per-player anti-spam rule (same key at most once per 20 ticks).
 */
public final class Messages {

    public static final String FILE = "messages.yml";
    private static final int THROTTLE_TICKS = 20;
    /** File format version key shared by config.yml and messages.yml, not a message. */
    public static final String VERSION_KEY = "config-version";
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String GEM_PREFIX = "gem.";
    private static final String GEM_PLACEHOLDER = "%gem%";

    /**
     * Anti-spam state for {@link #send}: replies to what a player does (errors, warnings, command replies), at most
     * one per key and second. Broadcasts and event notifications ({@link #notify}) are never throttled. Shared
     * between reloads, owned by the plugin, main thread only.
     */
    public static final class Throttle {
        private final Map<UUID, Map<String, Integer>> lastSent = new HashMap<>();

        boolean allow(UUID player, String key) {
            int now = Bukkit.getCurrentTick();
            Map<String, Integer> perKey = lastSent.computeIfAbsent(player, k -> new HashMap<>());
            Integer prev = perKey.get(key);
            if (prev != null && now - prev < THROTTLE_TICKS) return false;
            perKey.put(key, now);
            return true;
        }

        public void forget(UUID player) {
            lastSent.remove(player);
        }

        public void clear() {
            lastSent.clear();
        }
    }

    private final Map<String, String> strings;
    private final Map<String, List<String>> lists;
    private final String prefix;
    /** Palette tags with {@code <type>} = the palette text colour (no type in context). */
    private final TagResolver palette;
    /** Palette tags with {@code <type>} = the colour of that type, keyed by type id. */
    private final Map<String, TagResolver> typed;
    private final DurationFormat.Units units;
    private final Throttle throttle;
    private final boolean smallCaps;
    private final char decimalSeparator;

    private Messages(Map<String, String> strings, Map<String, List<String>> lists, TagResolver palette,
                     Map<String, TagResolver> typed, Throttle throttle, boolean smallCaps, char decimalSeparator) {
        this.strings = strings;
        this.lists = lists;
        this.prefix = strings.getOrDefault("prefix", "");
        this.palette = palette;
        this.typed = typed;
        this.throttle = throttle;
        this.smallCaps = smallCaps;
        this.decimalSeparator = decimalSeparator;
        this.units = new DurationFormat.Units(
                strings.getOrDefault("units.second", "giây"),
                strings.getOrDefault("units.minute", "phút"),
                strings.getOrDefault("units.hour", "giờ"),
                strings.getOrDefault("units.day", "ngày"));
    }

    /**
     * Loads {@code messages.yml}, filling missing or invalid keys from the bundled defaults.
     *
     * @throws IOException                   if the file cannot be read
     * @throws InvalidConfigurationException if the file is not valid YAML
     */
    public static Messages load(File file, InputStream bundledDefaults, CoreConfig config, Logger logger,
                                Throttle throttle) throws IOException, InvalidConfigurationException {
        YamlConfiguration defaults = readDefaults(bundledDefaults);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return parse(yaml, defaults, config, logger, throttle);
    }

    /** Reads the bundled {@code messages.yml}. */
    public static YamlConfiguration readDefaults(InputStream bundledDefaults) throws IOException, InvalidConfigurationException {
        YamlConfiguration defaults = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(bundledDefaults, StandardCharsets.UTF_8)) {
            defaults.load(reader);
        }
        return defaults;
    }

    /** Builds messages from a parsed file, filling missing or invalid keys from {@code defaults}. */
    public static Messages parse(YamlConfiguration yaml, YamlConfiguration defaults, CoreConfig config, Logger logger,
                                 Throttle throttle) {
        Map<String, String> strings = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        String gem = gemName(config);
        int missingGem = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || key.equals(VERSION_KEY)) continue;
            if (key.startsWith(GEM_PREFIX) && !yaml.contains(key)) {
                // files written before 1.3.0 have no gem messages: one info line instead of one warning per key
                missingGem++;
                if (defaults.isList(key)) {
                    List<String> styled = new ArrayList<>();
                    for (String line : defaults.getStringList(key)) styled.add(style(line, config, gem));
                    lists.put(key, List.copyOf(styled));
                } else {
                    strings.put(key, style(defaults.getString(key, ""), config, gem));
                }
                continue;
            }
            if (defaults.isList(key)) {
                List<String> value;
                if (yaml.isList(key)) {
                    value = yaml.getStringList(key);
                } else {
                    if (yaml.contains(key)) {
                        logger.warning("[" + FILE + "] Invalid value for '" + key + "': expected a list, using default");
                    } else {
                        logger.warning("[" + FILE + "] Missing key '" + key + "', using default");
                    }
                    value = defaults.getStringList(key);
                }
                List<String> styled = new ArrayList<>(value.size());
                for (String line : value) styled.add(style(line, config, gem));
                lists.put(key, List.copyOf(styled));
            } else {
                String value;
                if (yaml.isString(key)) {
                    value = yaml.getString(key);
                } else {
                    if (yaml.contains(key)) {
                        logger.warning("[" + FILE + "] Invalid value for '" + key + "': '" + yaml.get(key)
                                + "' (expected text), using default");
                    } else {
                        logger.warning("[" + FILE + "] Missing key '" + key + "', using default");
                    }
                    value = defaults.getString(key, "");
                }
                strings.put(key, style(value, config, gem));
            }
        }
        if (missingGem > 0) {
            logger.info("[" + FILE + "] " + missingGem + " gem message(s) missing, using the bundled defaults "
                    + "(copy them from the bundled messages.yml to customise)");
        }

        List<TagResolver> base = new ArrayList<>();
        for (Map.Entry<String, TextColor> e : config.palette().entrySet()) {
            base.add(TagResolver.resolver(e.getKey(), Tag.styling(e.getValue())));
        }
        base.add(TagResolver.resolver("gem", Tag.styling(config.gem().color())));
        TextColor textColor = config.palette().get("text");
        TagResolver palette = withType(base, textColor);
        Map<String, TagResolver> typed = new HashMap<>();
        for (BoostType type : config.typeList()) {
            typed.put(type.id(), withType(base, type.textColor() != null ? type.textColor() : textColor));
        }
        return new Messages(Map.copyOf(strings), Map.copyOf(lists), palette, Map.copyOf(typed), throttle, config.smallCaps(),
                config.decimalSeparator());
    }

    private static TagResolver withType(List<TagResolver> base, TextColor typeColor) {
        List<TagResolver> all = new ArrayList<>(base);
        all.add(TagResolver.resolver("type", Tag.styling(typeColor)));
        return TagResolver.resolver(all);
    }

    private TagResolver resolver(BoostType type) {
        if (type == null) return palette;
        return typed.getOrDefault(type.id(), palette);
    }

    /**
     * {@code %gem%} → the coloured gem display name. The name is resolved once here, so {@link #fill} never
     * sees {@code %gem%}.
     */
    private static String style(String template, CoreConfig config, String gem) {
        if (template == null) return "";
        return template.contains(GEM_PLACEHOLDER) ? template.replace(GEM_PLACEHOLDER, gem) : template;
    }

    /** {@code <gem>name</gem>}: the configured gem name in the gem colour. */
    private static String gemName(CoreConfig config) {
        return "<gem>" + MM.escapeTags(config.gem().displayName()) + "</gem>";
    }

    // ------------------------------------------------------------------ templates

    /** The styled template for a key, {@code ""} if unknown. */
    public String raw(String key) {
        return strings.getOrDefault(key, "");
    }

    /** The styled template list for a key, empty if unknown. */
    public List<String> rawList(String key) {
        return lists.getOrDefault(key, List.of());
    }

    public DurationFormat.Units units() {
        return units;
    }

    public String duration(long seconds) {
        return DurationFormat.format(seconds, units);
    }

    /** A multiplier in tenths for {@code %multiplier%}-like placeholders: {@code 15} → {@code 1.5} (Update 04 D7). */
    public String multiplier(int tenths) {
        return Multipliers.format(tenths, decimalSeparator);
    }

    /** The click word ({@code Chuột phải} / {@code Chuột trái}) for the configured use click. */
    public String click(CoreConfig.UseClick useClick) {
        return raw(useClick == CoreConfig.UseClick.LEFT ? "click.left" : "click.right");
    }

    /**
     * Fills {@code %key%} placeholders. Values are escaped so they can never inject tags.
     *
     * @param placeholders alternating key, value pairs
     */
    public static String fill(String template, Object... placeholders) {
        if (template.isEmpty() || placeholders.length == 0 || template.indexOf('%') < 0) return template;
        String out = template;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String token = "%" + placeholders[i] + "%";
            if (out.contains(token)) {
                out = out.replace(token, MM.escapeTags(String.valueOf(placeholders[i + 1])));
            }
        }
        return out;
    }

    /** Parses a filled template with the palette tags; {@code <type>} uses the palette text colour. */
    public Component parse(String filled) {
        return MM.deserialize(filled, palette);
    }

    /** Parses a filled template with the palette tags; {@code <type>} uses the colour of {@code type}. */
    public Component parse(BoostType type, String filled) {
        return MM.deserialize(filled, resolver(type));
    }

    /** Renders a template with placeholders, no prefix. */
    public Component render(String template, Object... placeholders) {
        return parse(fill(template, placeholders));
    }

    /** Renders a template with placeholders and the colour of {@code type} for {@code <type>}, no prefix. */
    public Component render(BoostType type, String template, Object... placeholders) {
        return parse(type, fill(template, placeholders));
    }

    /** Renders a template for an item name or lore line: small caps (if enabled), italic explicitly disabled. */
    public Component renderItem(BoostType type, String template, Object... placeholders) {
        return parseStyled(type, fill(template, placeholders))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /**
     * Parses a filled template with small caps (if enabled): bottle items and bossbar titles only, chat stays
     * in the normal font. Filled values are converted too; tags are never converted.
     */
    public Component parseStyled(BoostType type, String filled) {
        return parse(type, smallCaps ? SmallCaps.convert(filled) : filled);
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends a keyed reply to something the sender did (an error, a warning, a command result), with prefix; empty
     * messages are skipped; players are throttled per key so spamming a click or a command cannot flood the chat.
     * Use {@link #notify} for events that must never be dropped.
     */
    public void send(CommandSender to, String key, Object... placeholders) {
        send(to, (BoostType) null, key, placeholders);
    }

    /** Like {@link #send(CommandSender, String, Object...)} with {@code <type>} coloured for {@code type}. */
    public void send(CommandSender to, BoostType type, String key, Object... placeholders) {
        String template = raw(key);
        if (template.isEmpty() || to == null) return;
        if (to instanceof Player player && !throttle.allow(player.getUniqueId(), key)) return;
        to.sendMessage(parse(type, prefix + fill(template, placeholders)));
    }

    /**
     * Sends a keyed chat message with prefix and without throttling: for events that must never be dropped
     * (a bottle used or expired, a bottle or gems received, a transfer received). Empty messages are skipped.
     */
    public void notify(CommandSender to, String key, Object... placeholders) {
        notify(to, (BoostType) null, key, placeholders);
    }

    /** Like {@link #notify(CommandSender, String, Object...)} with {@code <type>} coloured for {@code type}. */
    public void notify(CommandSender to, BoostType type, String key, Object... placeholders) {
        String template = raw(key);
        if (template.isEmpty() || to == null) return;
        to.sendMessage(parse(type, prefix + fill(template, placeholders)));
    }

    /**
     * Shows a keyed title and subtitle (no prefix, no throttling) with {@code <type>} coloured for {@code type}.
     * Skipped when both templates are empty.
     */
    public void title(Player to, BoostType type, String titleKey, String subtitleKey, Object... placeholders) {
        String title = raw(titleKey);
        String subtitle = raw(subtitleKey);
        if ((title.isEmpty() && subtitle.isEmpty()) || to == null) return;
        to.showTitle(Title.title(render(type, title, placeholders), render(type, subtitle, placeholders)));
    }

    /** Sends a keyed message to every online player and the console, never throttled. */
    public void broadcast(String key, Object... placeholders) {
        broadcast((BoostType) null, key, placeholders);
    }

    /** Like {@link #broadcast(String, Object...)} with {@code <type>} coloured for {@code type}. */
    public void broadcast(BoostType type, String key, Object... placeholders) {
        String template = raw(key);
        if (template.isEmpty()) return;
        Component component = parse(type, prefix + fill(template, placeholders));
        for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(component);
        Bukkit.getConsoleSender().sendMessage(component);
    }

    /** Sends a line without prefix and without throttling (multi-line outputs); empty lines are skipped. */
    public void sendLine(CommandSender to, String template, Object... placeholders) {
        sendLine(to, (BoostType) null, template, placeholders);
    }

    /** Like {@link #sendLine(CommandSender, String, Object...)} with {@code <type>} coloured for {@code type}. */
    public void sendLine(CommandSender to, BoostType type, String template, Object... placeholders) {
        if (template.isEmpty() || to == null) return;
        to.sendMessage(render(type, template, placeholders));
    }
}
