package com.dex.boxcore.display;

import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A title is parsed once with a marker; the per-second update only replaces the marker with the remaining time. */
class BossBarTitleTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static YamlConfiguration bundled(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = BossBarTitleTest.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    private static Messages messages(boolean smallCaps) throws Exception {
        YamlConfiguration yaml = bundled("config.yml");
        yaml.set("style.small-caps", smallCaps);
        CoreConfig config = CoreConfig.parse(yaml, Logger.getAnonymousLogger(), m -> true);
        return Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
    }

    private static BoostType quest() throws Exception {
        return CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true).type("quest");
    }

    @Test
    void sameTextAsParsingTheWholeTitle() throws Exception {
        Messages msg = messages(false);
        BoostType quest = quest();
        Component base = BossBarService.personalTitle(msg, quest, 15, 35, 20, 0);
        Component title = BossBarService.withRemaining(base, "20:00");
        assertEquals("Boost Quest x1.5 | 20:00 | tổng x3.5", PLAIN.serialize(title));
        assertEquals("Boost Quest x1.5 | 19:59 | tổng x3.5", PLAIN.serialize(BossBarService.withRemaining(base, "19:59")));
        assertTrue(!PLAIN.serialize(base).isEmpty() && PLAIN.serialize(base).contains(BossBarService.REMAINING));
    }

    @Test
    void remainingTimeKeepsTheStyleOfThePlaceholder() throws Exception {
        Messages msg = messages(false);
        Component title = BossBarService.withRemaining(BossBarService.personalTitle(msg, quest(), 20, 20, 0, 0),
                "05:07");
        // bundled template: "... <gray>| <aqua>%remaining%"
        assertEquals(NamedTextColor.AQUA, styleOf(title, "05:07", Style.empty()).color());
    }

    @Test
    void markerSurvivesSmallCaps() throws Exception {
        Messages msg = messages(true);
        Component title = BossBarService.withRemaining(BossBarService.personalTitle(msg, quest(), 20, 20, 0, 0),
                "01:00");
        assertTrue(PLAIN.serialize(title).contains("01:00"), PLAIN.serialize(title));
        assertTrue(!PLAIN.serialize(title).contains(BossBarService.REMAINING));
    }

    /** Merged style of the text leaf whose content contains {@code text}. */
    private static Style styleOf(Component c, String text, Style parent) {
        Style merged = c.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (c instanceof TextComponent t && t.content().contains(text)) return merged;
        for (Component child : c.children()) {
            Style found = styleOf(child, text, merged);
            if (found != null) return found;
        }
        return null;
    }
}
