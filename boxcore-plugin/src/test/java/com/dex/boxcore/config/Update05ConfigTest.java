package com.dex.boxcore.config;

import org.bukkit.Material;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Update 05 E1 / E2 / messages: bottle materials, upgraded default texts, new keys without warning floods. */
class Update05ConfigTest {

    private static YamlConfiguration bundled(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = Update05ConfigTest.class.getClassLoader().getResourceAsStream(name)) {
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
    void bottleMaterialPerType() throws Exception {
        CoreConfig bundled = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        assertEquals(Material.HONEY_BOTTLE, bundled.bottleMaterial(bundled.type("mining")));
        assertEquals(Material.POTION, bundled.bottleMaterial(bundled.type("quest")));

        // a 1.4.0 config.yml has no bottle-material: the shipped types still get theirs, others the global material
        YamlConfiguration old = bundled("config.yml");
        old.set("types.mining.bottle-material", null);
        old.set("types.quest.bottle-material", null);
        old.set("types.event.max-multiplier", 5);
        old.set("types.event.max-duration", "1h");
        CoreConfig config = CoreConfig.parse(old, Logger.getAnonymousLogger(), m -> true);
        assertEquals(Material.HONEY_BOTTLE, config.bottleMaterial(config.type("mining")));
        assertEquals(Material.POTION, config.bottleMaterial(config.type("quest")));
        assertEquals(Material.EXPERIENCE_BOTTLE, config.bottleMaterial(config.type("event")));

        YamlConfiguration bad = bundled("config.yml");
        bad.set("types.mining.bottle-material", "NOT_A_MATERIAL");
        Capture capture = new Capture();
        CoreConfig withWarning = CoreConfig.parse(bad, logger(capture), m -> true);
        assertEquals(Material.HONEY_BOTTLE, withWarning.bottleMaterial(withWarning.type("mining")));
        assertTrue(String.join("\n", capture.messages).contains("types.mining.bottle-material"));
    }

    @Test
    void oldDefaultTextsAreUpgradedEditedOnesKept() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        YamlConfiguration server = bundled("messages.yml");
        for (var e : Messages.REPLACED_DEFAULTS.entrySet()) server.set(e.getKey(), e.getValue());
        Capture capture = new Capture();
        Messages messages = Messages.parse(server, bundled("messages.yml"), config, logger(capture),
                new Messages.Throttle());
        YamlConfiguration defaults = bundled("messages.yml");
        assertEquals(defaults.getString("bottle-item.name"), messages.raw("bottle-item.name"));
        assertEquals(defaults.getStringList("bottle-item.lore"), messages.rawList("bottle-item.lore"));
        assertEquals(defaults.getStringList("booster.guide"), messages.rawList("booster.guide"));
        assertTrue(String.join("\n", capture.messages).contains("1.4.0 default text"));

        server.set("bottle-item.name", "<red>My bottle %type_name%");
        Messages edited = Messages.parse(server, bundled("messages.yml"), config, Logger.getAnonymousLogger(),
                new Messages.Throttle());
        assertEquals("<red>My bottle %type_name%", edited.raw("bottle-item.name"), "an edited text is kept");
    }

    @Test
    void newKeysMissingFromAnOldFileGiveOneInfoLine() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        YamlConfiguration old = bundled("messages.yml");
        for (String key : List.of("clan", "clan-bottle-item", "bottle.extended", "bottle.full", "bottle.refused-lower",
                "bottle.confirm-capped", "give.done-clan", "give.received-clan", "bossbar.clan", "bossbar.clan-suffix",
                "check.clan", "check.clan-used", "check.clan-none")) {
            old.set(key, null);
        }
        Capture capture = new Capture();
        Messages messages = Messages.parse(old, bundled("messages.yml"), config, logger(capture), new Messages.Throttle());
        String log = String.join("\n", capture.messages);
        assertFalse(log.contains("Missing key"), log);
        assertTrue(log.contains("added in 1.5.0"), log);
        assertFalse(messages.raw("clan.not-allowed").isEmpty());
        assertFalse(messages.rawList("clan-bottle-item.lore").isEmpty());
    }

    @Test
    void everyKeyTheCodeUsesExists() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        for (String key : List.of("bottle.extended", "bottle.full", "bottle.refused-lower", "bottle.confirm-capped",
                "bottle.confirm-higher", "bottle.confirm-no-effect", "bottle.used", "bottle.replaced",
                "clan-bottle-item.name", "give.done-clan", "give.received-clan", "clan.staff-name", "clan.started",
                "clan.extended", "clan.replaced", "clan.expired", "clan.stopped", "clan.confirm-replace",
                "clan.confirm-capped", "clan.refused-lower", "clan.full", "clan.no-clan", "clan.not-allowed",
                "clan.unavailable", "bossbar.clan", "bossbar.clan-suffix", "check.clan", "check.clan-used",
                "check.clan-none")) {
            assertFalse(messages.raw(key).isEmpty(), key);
        }
        assertFalse(messages.rawList("clan-bottle-item.lore").isEmpty());
    }

    @Test
    void loreLinesAreShort() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        Messages messages = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        BoostType mining = config.type("mining");
        for (String key : List.of("bottle-item.lore", "clan-bottle-item.lore")) {
            for (String line : messages.rawList(key)) {
                if (line.contains("%description%")) continue; // wrapped by description-width
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(messages.renderItem(mining, line, "multiplier", 10, "duration", "20 phút",
                                "type_name", "Mining", "click", "Chuột phải"));
                assertTrue(plain.length() <= 34, key + ": '" + plain + "' is " + plain.length() + " characters");
            }
        }
    }
}
