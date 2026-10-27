package com.dex.boxcore.config;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoadingTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static YamlConfiguration bundled(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = ConfigLoadingTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    private static final class Capture extends Handler {
        final List<String> messages = new ArrayList<>();
        @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private static Logger logger(Capture capture) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        return logger;
    }

    @Test
    void bundledConfigLoadsWithoutWarnings() throws Exception {
        Capture capture = new Capture();
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), logger(capture), m -> true);
        assertTrue(capture.messages.isEmpty(), capture.messages.toString());
        assertEquals(List.of("mining", "quest", "mob"), new ArrayList<>(config.types().keySet()));
        BoostType mining = config.type("mining");
        assertEquals(0, mining.index());
        assertEquals(1000, mining.maxTenths());
        assertTrue(!mining.decimal(), "mining stays whole numbers");
        assertTrue(config.type("quest").decimal(), "quest allows decimals");
        assertEquals('.', config.decimalSeparator());
        assertEquals(86400, mining.maxDurationSeconds());
        assertEquals(BossBar.Color.YELLOW, mining.color());
        assertEquals(TextColor.fromHexString("#FFAA00"), mining.textColor());
        assertEquals(1, config.type("quest").index());
        assertEquals(BossBar.Color.BLUE, config.type("quest").color());
        assertEquals(TextColor.fromHexString("#55FFFF"), config.type("quest").textColor());
        assertEquals(TextColor.fromHexString("#FFFFFF"), config.palette().get("text"));
        assertEquals(TextColor.fromHexString("#C8C8C8"), config.palette().get("muted"));
        assertEquals(TextColor.fromHexString("#FFD166"), config.palette().get("accent"));
        assertEquals(TextColor.fromHexString("#55FF55"), config.palette().get("success"));
        assertEquals(TextColor.fromHexString("#FF5555"), config.palette().get("error"));
        assertEquals(CoreConfig.UseClick.RIGHT, config.useClick());
        assertEquals(10, config.useCooldownTicks());
        assertEquals(3, config.confirmWindowSeconds());
        // the bundled config.yml holds the owner's value (edited 2026-10-20); 30 stays the default when missing
        assertEquals(10, config.permissionRefreshSeconds());
        assertEquals(5, config.saveDebounceSeconds());
        assertEquals("Asia/Ho_Chi_Minh", config.timezone().getId());
        assertTrue(!config.smallCaps(), "small caps are off by default");
    }

    @Test
    void invalidValuesFallBackWithWarnings() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("types.Bad-Id.max-multiplier", 5);
        yaml.set("types.bad_mult.max-multiplier", 1);
        yaml.set("types.bad_mult.max-duration", "1h");
        yaml.set("types.bad_dur.max-multiplier", 5);
        yaml.set("types.bad_dur.max-duration", "abc");
        yaml.set("types.quest.bossbar-color", "ORANGE");
        yaml.set("types.mining.color", "orange");
        yaml.set("bottle.use-click", "MIDDLE");
        yaml.set("bottle.use-cooldown-ticks", "ten");
        yaml.set("permission-refresh-seconds", -1);
        yaml.set("timezone", "Mars/Olympus");
        yaml.set("style.palette.accent", "gold");
        Capture capture = new Capture();
        CoreConfig config = CoreConfig.parse(yaml, logger(capture), m -> true);

        assertEquals(List.of("mining", "quest", "mob"), new ArrayList<>(config.types().keySet()));
        assertEquals(BossBar.Color.WHITE, config.type("quest").color());
        assertNull(config.type("mining").textColor());
        assertEquals(CoreConfig.UseClick.RIGHT, config.useClick());
        assertEquals(10, config.useCooldownTicks());
        assertEquals(30, config.permissionRefreshSeconds());
        assertEquals("Asia/Ho_Chi_Minh", config.timezone().getId());
        String log = String.join("\n", capture.messages);
        for (String key : List.of("Bad-Id", "bad_mult", "bad_dur", "types.quest.bossbar-color", "types.mining.color", "bottle.use-click",
                "bottle.use-cooldown-ticks", "permission-refresh-seconds", "timezone", "style.palette.accent")) {
            assertTrue(log.contains(key), "warning should name " + key + ":\n" + log);
        }
    }

    @Test
    void bottleRendersLikeSpecWithoutSmallCaps() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("style.small-caps", false);
        CoreConfig config = CoreConfig.parse(yaml, Logger.getAnonymousLogger(), m -> true);
        Capture capture = new Capture();
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                logger(capture), new Messages.Throttle());
        assertTrue(capture.messages.isEmpty(), capture.messages.toString());

        BoostType mining = config.type("mining");
        Object[] ph = {"type", "mining", "type_name", mining.displayName(), "multiplier", 10,
                "duration", messages.duration(1200), "description", mining.description("10"),
                "click", messages.click(config.useClick())};
        Component name = messages.renderItem(mining, messages.raw("bottle-item.name"), ph);
        assertEquals("Boost Mining [x10]", PLAIN.serialize(name));
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
        assertEquals(TextColor.fromHexString("#FFAA00"), colorOf(name));
        assertTrue(!hasBold(name), "Update 05 E2: the bottle name is not bold");

        BoostType quest = config.type("quest");
        Component questName = messages.renderItem(quest, messages.raw("bottle-item.name"),
                "type_name", quest.displayName(), "multiplier", 10);
        assertEquals("Boost Quest [x10]", PLAIN.serialize(questName));
        assertEquals(TextColor.fromHexString("#55FFFF"), colorOf(questName));

        List<String> lore = new ArrayList<>();
        for (String line : messages.rawList("bottle-item.lore")) lore.add(PLAIN.serialize(messages.renderItem(mining, line, ph)));
        assertEquals(List.of(
                "Tăng x10 vật phẩm nhận được khi đào các khối",
                "",
                "Thông tin:",
                "• Loại: Mining",
                "• Hệ số: x10",
                "• Thời gian: 20 phút",
                "",
                "Lưu ý:",
                "• Cùng hệ số: cộng thời gian",
                "• Hệ số thấp hơn: không dùng được",
                "• Hướng dẫn: /booster",
                "",
                "[Chuột phải để kích hoạt]"), lore);
    }

    @Test
    void smallCapsOnlyOnBossbarsChatAndBottlesAreNormal() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("style.small-caps", true);
        CoreConfig config = CoreConfig.parse(yaml, Logger.getAnonymousLogger(), m -> true);
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        BoostType mining = config.type("mining");
        // Update 05 E2: bottles never use small caps
        Component name = messages.renderItem(mining, messages.raw("bottle-item.name"),
                "type_name", mining.displayName(), "multiplier", 10);
        assertEquals("Boost Mining [x10]", PLAIN.serialize(name));
        Component clanName = messages.renderItem(mining, messages.raw("clan-bottle-item.name"),
                "type_name", mining.displayName(), "multiplier", 10);
        assertEquals("Boost Mining - Bang hội [x10]", PLAIN.serialize(clanName));
        Component bar = messages.parseStyled(mining, Messages.fill(messages.raw("bossbar.personal"),
                "type_name", mining.displayName(), "multiplier", 10, "remaining", "20:00"));
        assertEquals("ʙᴏᴏꜱᴛ ᴍɪɴɪɴɢ x10 | 20:00", PLAIN.serialize(bar));
        Component offline = messages.render(messages.raw("error.player-offline"), "player", "Steve_<b>");
        assertEquals("✘ Người chơi Steve_<b> không trực tuyến", PLAIN.serialize(offline));
        assertEquals("20 phút", messages.duration(1200));
    }

    @Test
    void missingMessageKeysUseDefaults() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        YamlConfiguration partial = new YamlConfiguration();
        partial.set("bottle.used", "<success>custom %multiplier%");
        partial.set("bottle-item.lore", "not a list");
        Capture capture = new Capture();
        Messages messages = Messages.parse(partial, bundled("messages.yml"), config, logger(capture),
                new Messages.Throttle());
        assertEquals("<success>custom %multiplier%", messages.raw("bottle.used"));
        assertEquals(13, messages.rawList("bottle-item.lore").size());
        assertTrue(String.join("\n", capture.messages).contains("bottle-item.lore"));
    }

    @Test
    void bossbarTitlesAreBoldOnlyOnTheBoostPart() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        BoostType mining = config.type("mining");
        String filled = Messages.fill(messages.raw("bossbar.personal"), "type_name", mining.displayName(),
                "multiplier", 3, "remaining", "20:00")
                + Messages.fill(messages.raw("bossbar.personal-suffix-total"), "effective", 8);
        Component title = messages.parseStyled(mining, filled);
        assertEquals("Boost Mining x3 | 20:00 | tổng x8", PLAIN.serialize(title));
        List<Component> leaves = new ArrayList<>();
        flatten(title, leaves);
        boolean sawBoldOrange = false;
        for (Component leaf : leaves) {
            String text = PLAIN.serialize(leaf);
            if (text.isBlank()) continue;
            boolean bold = leaf.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
            if (text.contains("Boost")) {
                assertTrue(bold, "boost part must be bold: " + text);
                assertEquals(TextColor.fromHexString("#FFAA00"), leaf.color());
                sawBoldOrange = true;
            } else {
                assertTrue(!bold, "only the boost part is bold, not: " + text);
            }
        }
        assertTrue(sawBoldOrange);
    }

    @Test
    void newTextsOfUpdate01() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("style.small-caps", false);
        CoreConfig config = CoreConfig.parse(yaml, Logger.getAnonymousLogger(), m -> true);
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        assertEquals("Boost global không cộng dồn với multiple rank và booster cá nhân",
                PLAIN.serialize(messages.render(messages.raw("global.no-stack-note"))));
        assertEquals("Boost Mining x2 cũ (còn 20:00) đã được thay bằng x3",
                PLAIN.serialize(messages.render(config.type("mining"), messages.raw("bottle.replaced"),
                        "type_name", "Mining", "old", 2, "remaining", "20:00", "multiplier", 3)));
        List<String> guide = new ArrayList<>();
        for (String line : messages.rawList("booster.guide")) {
            guide.add(PLAIN.serialize(messages.render(line, "click", messages.click(config.useClick()))));
        }
        assertTrue(guide.contains("• Multiple rank cộng dồn với booster cá nhân (chỉ tính chai đang chạy)"));
        assertTrue(guide.contains("• Boost global không cộng dồn với multiple rank và booster cá nhân"));
        assertTrue(guide.stream().noneMatch(l -> l.contains("Chỉ bội số cao nhất")));
    }

    @Test
    void emptyNoStackNoteIsKeptEmpty() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        YamlConfiguration user = bundled("messages.yml");
        user.set("global.no-stack-note", "");
        Messages messages = Messages.parse(user, bundled("messages.yml"), config, Logger.getAnonymousLogger(),
                new Messages.Throttle());
        assertEquals("", messages.raw("global.no-stack-note"));
    }

    // ------------------------------------------------------------------ gems (Update 03)

    @Test
    void bundledGemSection() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        com.dex.boxcore.gem.GemConfig gem = config.gem();
        assertTrue(gem.sectionPresent());
        assertEquals("Gem", gem.displayName());
        assertEquals(TextColor.fromHexString("#8245F8"), gem.color());
        assertEquals(0, gem.startingBalance());
        assertEquals(1_000_000_000_000L, gem.maxBalance());
        assertEquals(1, gem.payMinAmount());
        assertEquals(3, gem.payCooldownSeconds());
        assertEquals(10, gem.topSize());
        assertEquals(60, gem.topRefreshSeconds());
        assertTrue(gem.topExclude().isEmpty());
        assertEquals("-", gem.placeholderUnknown());
        assertEquals(180, gem.logKeepDays());
    }

    @Test
    void configWithoutGemSectionUsesDefaultsWithOneInfoLine() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("gem", null);
        Capture capture = new Capture();
        CoreConfig config = CoreConfig.parse(yaml, logger(capture), m -> true);
        assertEquals(1, capture.messages.size(), capture.messages.toString());
        assertTrue(capture.messages.get(0).contains("no 'gem' section"));
        assertEquals(1_000_000_000_000L, config.gem().maxBalance());
        assertTrue(!config.gem().sectionPresent());
    }

    @Test
    void invalidGemValuesFallBackWithWarnings() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("gem.max-balance", 5_000_000_000_000L);
        yaml.set("gem.starting-balance", -1);
        yaml.set("gem.color", "cyan");
        yaml.set("gem.top.size", 500);
        yaml.set("gem.top.refresh-seconds", 1);
        yaml.set("gem.pay.min-amount", "one");
        Capture capture = new Capture();
        com.dex.boxcore.gem.GemConfig gem = CoreConfig.parse(yaml, logger(capture), m -> true).gem();
        assertEquals(1_000_000_000_000L, gem.maxBalance());
        assertEquals(0, gem.startingBalance());
        assertEquals(TextColor.fromHexString("#8245F8"), gem.color());
        assertEquals(10, gem.topSize());
        assertEquals(60, gem.topRefreshSeconds());
        assertEquals(1, gem.payMinAmount());
        String log = String.join("\n", capture.messages);
        for (String key : List.of("gem.max-balance", "gem.starting-balance", "gem.color", "gem.top.size",
                "gem.top.refresh-seconds", "gem.pay.min-amount")) {
            assertTrue(log.contains(key), "warning should name " + key + ":\n" + log);
        }
    }

    @Test
    void gemNameIsColouredInMessages() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("style.small-caps", false);
        CoreConfig config = CoreConfig.parse(yaml, Logger.getAnonymousLogger(), m -> true);
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        Component line = messages.render(messages.raw("gem.balance"), "amount", "1.250");
        assertEquals("Số dư: 1.250 Gem", PLAIN.serialize(line));
        List<Component> leaves = new ArrayList<>();
        flatten(line, leaves);
        // "1.250 Gem" is one piece in the gem colour (1.5.0)
        Component gemLeaf = leaves.stream().filter(c -> PLAIN.serialize(c).contains("Gem")).findFirst().orElseThrow();
        assertEquals(TextColor.fromHexString("#8245F8"), gemLeaf.color());

        // 1.5.0: every gem amount has the gem colour too, and the text after it goes back to its own colour
        TextColor gem = TextColor.fromHexString("#8245F8");
        for (String key : Messages.REPLACED_GEM_DEFAULTS.keySet()) {
            Component c = messages.render(messages.raw(key), "amount", "777", "balance", "777", "max", "777", "min", "777",
                    "delta", "+777", "player", "Alice", "time", "01/01 10:00", "reason", "test", "rank", "1");
            List<Component> parts = new ArrayList<>();
            flatten(c, parts);
            List<Component> numbers = parts.stream().filter(p -> PLAIN.serialize(p).contains("777")).toList();
            assertFalse(numbers.isEmpty(), key);
            for (Component n : numbers) assertEquals(gem, n.color(), key + ": " + PLAIN.serialize(c));
            for (Component p : parts) {
                if (PLAIN.serialize(p).contains("Alice") || PLAIN.serialize(p).contains("khỏi")
                        || PLAIN.serialize(p).contains("test")) {
                    assertNotEquals(gem, p.color(), key + ": '" + PLAIN.serialize(p) + "' should not be purple");
                }
            }
        }
    }

    @Test
    void oldGemTextsAreUpgradedEditedOnesKept() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        YamlConfiguration server = bundled("messages.yml");
        for (var e : Messages.REPLACED_GEM_DEFAULTS.entrySet()) server.set(e.getKey(), e.getValue().get(e.getValue().size() - 1));
        server.set("gem.balance", "<red>Ví: %amount%");
        Messages messages = Messages.parse(server, bundled("messages.yml"), config, Logger.getAnonymousLogger(),
                new Messages.Throttle());
        Messages fresh = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        for (String key : Messages.REPLACED_GEM_DEFAULTS.keySet()) {
            if (key.equals("gem.balance")) continue;
            assertEquals(fresh.raw(key), messages.raw(key), key);
        }
        assertEquals("<red>Ví: %amount%", messages.raw("gem.balance"), "an edited text is kept");
    }

    @Test
    void oldMessagesWithoutGemKeysLogOneInfoLine() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        YamlConfiguration old = bundled("messages.yml");
        old.set("gem", null);
        Capture capture = new Capture();
        Messages messages = Messages.parse(old, bundled("messages.yml"), config, logger(capture),
                new Messages.Throttle());
        assertEquals(1, capture.messages.size(), capture.messages.toString());
        assertTrue(capture.messages.get(0).contains("gem message(s) missing"));
        assertTrue(!messages.raw("gem.pay.sent").isEmpty());
    }

    private static TextColor colorOf(Component component) {
        List<Component> leaves = new ArrayList<>();
        flatten(component, leaves);
        for (Component leaf : leaves) {
            if (!PLAIN.serialize(leaf).isBlank()) return leaf.color();
        }
        return null;
    }

    private static boolean hasBold(Component component) {
        List<Component> leaves = new ArrayList<>();
        flatten(component, leaves);
        return leaves.stream().anyMatch(c -> c.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE);
    }

    /** Leaves with inherited style merged in, children excluded from each leaf. */
    private static void flatten(Component component, List<Component> out) {
        flatten(component, Style.empty(), out);
    }

    private static void flatten(Component c, Style parent, List<Component> out) {
        Style merged = c.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        out.add(c.children(List.of()).style(merged));
        for (Component child : c.children()) flatten(child, merged, out);
    }

    // ------------------------------------------------------------------ Update 04: decimal multipliers

    @Test
    void decimalFlagAndMaxMultiplier() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("types.quest.max-multiplier", 12.5);
        yaml.set("types.dec_missing.max-multiplier", 5);       // no decimal key: whole type
        yaml.set("types.dec_missing.max-duration", "1h");
        yaml.set("types.dec_bad.decimal", "yes-please");        // not a boolean: false + warning
        yaml.set("types.dec_bad.max-multiplier", 5);
        yaml.set("types.dec_bad.max-duration", "1h");
        yaml.set("types.whole_half.max-multiplier", 50.5);      // decimal max on a whole type: skipped
        yaml.set("types.whole_half.max-duration", "1h");
        yaml.set("types.dec_low.decimal", true);
        yaml.set("types.dec_low.max-multiplier", 1.1);          // smallest decimal max
        yaml.set("types.dec_low.max-duration", "1h");
        yaml.set("types.dec_one.decimal", true);
        yaml.set("types.dec_one.max-multiplier", 1);            // below x1.1: skipped
        yaml.set("types.dec_one.max-duration", "1h");
        yaml.set("types.too_big.max-multiplier", 10001);        // above x10000: skipped
        yaml.set("types.too_big.max-duration", "1h");
        yaml.set("types.two_decimals.decimal", true);
        yaml.set("types.two_decimals.max-multiplier", 2.25);    // two decimals: skipped
        yaml.set("types.two_decimals.max-duration", "1h");
        Capture capture = new Capture();
        CoreConfig config = CoreConfig.parse(yaml, logger(capture), m -> true);

        assertEquals(125, config.type("quest").maxTenths());
        assertTrue(!config.type("dec_missing").decimal());
        assertEquals(50, config.type("dec_missing").maxTenths());
        assertTrue(!config.type("dec_bad").decimal());
        assertEquals(11, config.type("dec_low").maxTenths());
        for (String skipped : List.of("whole_half", "dec_one", "too_big", "two_decimals")) {
            assertNull(config.type(skipped), skipped);
        }
        String log = String.join("\n", capture.messages);
        for (String key : List.of("types.dec_bad.decimal", "whole_half", "dec_one", "too_big", "two_decimals")) {
            assertTrue(log.contains(key), "warning should name " + key + ":\n" + log);
        }
        assertTrue(!log.contains("dec_missing"), log);
    }

    @Test
    void multiplierValidityFollowsDecimalFlag() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        BoostType mining = config.type("mining");
        BoostType quest = config.type("quest");
        assertTrue(quest.isValidMultiplier(11));
        assertTrue(quest.isValidMultiplier(15));
        assertTrue(quest.isValidMultiplier(1000));
        assertTrue(!quest.isValidMultiplier(10));
        assertTrue(!quest.isValidMultiplier(1001));
        assertTrue(mining.isValidMultiplier(20));
        assertTrue(!mining.isValidMultiplier(15));
        assertTrue(!mining.isValidMultiplier(25));
        assertTrue(!mining.isValidMultiplier(10));
    }

    @Test
    void decimalSeparatorIsConfigurable() throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("multiplier-decimal-separator", ",");
        CoreConfig config = CoreConfig.parse(yaml, Logger.getAnonymousLogger(), m -> true);
        assertEquals("1,5", config.formatMultiplier(15));
        assertEquals("2", config.formatMultiplier(20));
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        assertEquals("1,5", messages.multiplier(15));

        yaml.set("multiplier-decimal-separator", ";");
        Capture capture = new Capture();
        assertEquals('.', CoreConfig.parse(yaml, logger(capture), m -> true).decimalSeparator());
        assertTrue(String.join("\n", capture.messages).contains("multiplier-decimal-separator"));
    }

    @Test
    void decimalBottleName() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        BoostType quest = config.type("quest");
        Component name = messages.renderItem(quest, messages.raw("bottle-item.name"),
                "type", "quest", "type_name", quest.displayName(), "multiplier", messages.multiplier(15));
        assertEquals("Boost Quest [x1.5]", PLAIN.serialize(name));
        assertTrue(messages.raw("error.multiplier-decimal").contains("1.1"));
    }
}
