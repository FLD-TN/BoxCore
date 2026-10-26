package com.dex.boxcore.display;

import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Update 05 E3.5: the clan bar, and "đang dùng" on the personal bar when the clan boost is higher. */
class ClanBossBarTitleTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static YamlConfiguration bundled(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = ClanBossBarTitleTest.class.getClassLoader().getResourceAsStream(name)) {
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @Test
    void titles() throws Exception {
        CoreConfig config = CoreConfig.parse(bundled("config.yml"), Logger.getAnonymousLogger(), m -> true);
        Messages msg = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), config,
                Logger.getAnonymousLogger(), new Messages.Throttle());
        BoostType mining = config.type("mining");

        assertEquals("Boost Bang hội Mining x2 | 45:12",
                PLAIN.serialize(BossBarService.withRemaining(BossBarService.clanTitle(msg, mining, 20, 20), "45:12")));
        assertEquals("Boost Bang hội Mining x2 | 45:12 | đang dùng x5",
                PLAIN.serialize(BossBarService.withRemaining(BossBarService.clanTitle(msg, mining, 20, 50), "45:12")),
                "the player's own value is higher");
        assertEquals("Boost Mining x3 | 10:00 | đang dùng x5",
                PLAIN.serialize(BossBarService.withRemaining(
                        BossBarService.personalTitle(msg, mining, 30, 50, 0, 0, 50), "10:00")),
                "the clan boost determines the value");
        assertEquals("Boost Mining x3 | 10:00",
                PLAIN.serialize(BossBarService.withRemaining(
                        BossBarService.personalTitle(msg, mining, 30, 30, 0, 0, 20), "10:00")));
    }
}
