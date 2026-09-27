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
        assertEquals(List.of("mining", "quest"), new ArrayList<>(config.types().keySet()));
        BoostType mining = config.type("mining");
        assertEquals(0, mining.index());
        assertEquals(100, mining.maxMultiplier());
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
        assertEquals(5, config.confirmWindowSeconds());
        assertEquals(30, config.permissionRefreshSeconds());
        assertEquals(5, config.saveDebounceSeconds());
        assertEquals("Asia/Ho_Chi_Minh", config.timezone().getId());
        assertTrue(config.smallCaps());
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

        assertEquals(List.of("mining", "quest"), new ArrayList<>(config.types().keySet()));
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
                "duration", messages.duration(1200), "description", mining.description(10),
                "click", messages.click(config.useClick())};
        Component name = messages.renderItem(mining, messages.raw("bottle-item.name"), ph);
        assertEquals("Boost Mining x10", PLAIN.serialize(name));
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
        assertEquals(TextColor.fromHexString("#FFAA00"), colorOf(name));
        assertTrue(!hasBold(name), "bottle name must not be bold");

        BoostType quest = config.type("quest");
        Component questName = messages.renderItem(quest, messages.raw("bottle-item.name"),
                "type_name", quest.displayName(), "multiplier", 10);
        assertEquals("Boost Quest x10", PLAIN.serialize(questName));
        assertEquals(TextColor.fromHexString("#55FFFF"), colorOf(questName));

        List<String> lore = new ArrayList<>();
        for (String line : messages.rawList("bottle-item.lore")) lore.add(PLAIN.serialize(messages.renderItem(mining, line, ph)));
        assertEquals(List.of(
                "Tăng x10 vật phẩm nhận được khi đào các khối",
                "• Loại: mining",
                "• Multiple: x10",
                "• Thời gian: 20 phút",
                "",
                "(!) Hướng dẫn sử dụng tại /booster",
                "Chuột phải để sử dụng vật phẩm"), lore);
    }

    @Test
    void smallCapsAppliedToTemplatesNotValues() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        BoostType mining = config.type("mining");
        Component name = messages.renderItem(mining, messages.raw("bottle-item.name"),
                "type_name", mining.displayName(), "multiplier", 10);
        assertEquals("ʙᴏᴏꜱᴛ ᴍɪɴɪɴɢ x10", PLAIN.serialize(name));
        Component offline = messages.render(messages.raw("error.player-offline"), "player", "Steve_<b>");
        assertEquals("✘ ɴɢườɪ ᴄʜơɪ Steve_<b> ᴋʜôɴɢ ᴛʀựᴄ ᴛᴜʏếɴ", PLAIN.serialize(offline));
        assertEquals("20 ᴘʜúᴛ", messages.duration(1200));
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
        assertEquals("<success>ᴄᴜꜱᴛᴏᴍ %multiplier%", messages.raw("bottle.used"));
        assertEquals(7, messages.rawList("bottle-item.lore").size());
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
        Component title = messages.parse(mining, filled);
        assertEquals("ʙᴏᴏꜱᴛ ᴍɪɴɪɴɢ x3 • ᴄòɴ 20:00 • ᴛổɴɢ x8", PLAIN.serialize(title));
        List<Component> leaves = new ArrayList<>();
        flatten(title, leaves);
        boolean sawBoldOrange = false;
        for (Component leaf : leaves) {
            String text = PLAIN.serialize(leaf);
            if (text.isBlank()) continue;
            boolean bold = leaf.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
            if (text.contains("ʙᴏᴏꜱᴛ")) {
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
        assertTrue(guide.contains("• Multiple rank cộng dồn với booster cá nhân (chỉ tính chai cao nhất đang chạy)"));
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
}
