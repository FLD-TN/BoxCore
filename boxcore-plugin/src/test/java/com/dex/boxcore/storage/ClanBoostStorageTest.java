package com.dex.boxcore.storage;

import com.dex.boxcore.boost.BoostRegistry;
import com.dex.boxcore.boost.ClanBoost;
import com.dex.boxcore.boost.GlobalBoost;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Update 05 E3.6: clan boosts survive a restart in {@code clan-boosts.yml}; expired ones are dropped. */
class ClanBoostStorageTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    File dir;

    private BoostStorage storage(BoostRegistry registry) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getDataFolder()).thenReturn(dir);
        when(plugin.getLogger()).thenReturn(LOG);
        when(plugin.isEnabled()).thenReturn(false);
        return new BoostStorage(plugin, registry, () -> 5, new ErrorReporter(LOG));
    }

    @Test
    void roundTrip() throws Exception {
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        BoostRegistry before = new BoostRegistry();
        before.putClan(new ClanBoost(alpha, "mining", 20, NOW - 1000, NOW + 600_000));
        before.putClan(new ClanBoost(alpha, "quest", 15, NOW - 1000, NOW + 600_000));
        before.putClan(new ClanBoost(beta, "mining", 30, NOW - 1000, NOW + 1000));
        before.putGlobal(new GlobalBoost("mining", 40, NOW - 1000, NOW + 600_000));
        storage(before).saveSync();
        assertTrue(new File(dir, BoostStorage.CLAN_FILE).exists());

        BoostRegistry after = new BoostRegistry();
        storage(after).load(NOW + 5000); // beta's boost expired while the server was down
        assertEquals(new ClanBoost(alpha, "mining", 20, NOW - 1000, NOW + 600_000), after.clan(alpha, "mining"));
        assertEquals(15, after.clan(alpha, "quest").tenths(), "decimal multiplier kept");
        assertNull(after.clan(beta, "mining"));
        assertEquals(40, after.global("mining").tenths(), "the other files are unchanged");
    }

    @Test
    void invalidEntriesAreSkipped() throws Exception {
        UUID alpha = UUID.randomUUID();
        Files.writeString(new File(dir, BoostStorage.CLAN_FILE).toPath(), """
                not-a-uuid:
                  mining: {multiplier: 2, started-at: 1, expires-at: 9999999999999}
                %s:
                  mining: {multiplier: 2, started-at: 1, expires-at: 9999999999999}
                  quest: {multiplier: 1.25, started-at: 1, expires-at: 9999999999999}
                  broken: {multiplier: 2}
                """.formatted(alpha));
        BoostRegistry registry = new BoostRegistry();
        storage(registry).load(NOW);
        assertEquals(20, registry.clan(alpha, "mining").tenths());
        assertNull(registry.clan(alpha, "quest"));
        assertNull(registry.clan(alpha, "broken"));
        assertEquals(1, registry.allClans().size());
    }
}
