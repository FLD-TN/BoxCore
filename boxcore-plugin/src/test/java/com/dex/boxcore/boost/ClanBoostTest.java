package com.dex.boxcore.boost;

import com.dex.boxcore.api.ClanBoostResult;
import com.dex.boxcore.api.ClanBoostResult.Status;
import com.dex.boxcore.api.ClanProvider;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.display.BossBarService;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Update 05 E3 / E4: clan boost rules (start, extend, cap, replace, refuse) and the effective multiplier. */
class ClanBoostTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final long NOW = 1_800_000_000_000L;
    private static final long MIN = 60_000L;

    private final UUID alpha = UUID.randomUUID();
    private final UUID beta = UUID.randomUUID();
    private final UUID steve = UUID.randomUUID();
    private final Map<UUID, UUID> clanOf = new HashMap<>();

    private CoreConfig cfg;
    private BoostRegistry registry;
    private EffectiveCache cache;
    private ClanLink link;
    private BoostController controller;
    private BoostType mining;
    private BoostType quest;

    private static YamlConfiguration bundled(String name) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStream in = ClanBoostTest.class.getClassLoader().getResourceAsStream(name)) {
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return yaml;
    }

    @BeforeEach
    void setUp() throws Exception {
        cfg = CoreConfig.parse(bundled("config.yml"), LOG, m -> true);
        Messages msg = Messages.parse(bundled("messages.yml"), bundled("messages.yml"), cfg, LOG, new Messages.Throttle());
        registry = new BoostRegistry();
        cache = new EffectiveCache();
        link = new ClanLink(new ErrorReporter(LOG));
        controller = new BoostController(() -> cfg, () -> msg, new Messages.Throttle(), registry, cache,
                new BossBarService(() -> cfg, () -> msg), new ErrorReporter(LOG), Runnable::run, link);
        link.set(mock(Plugin.class), new ClanProvider() {
            @Override
            public UUID clanOf(UUID player) {
                return ClanBoostTest.this.clanOf.get(player);
            }

            @Override
            public boolean canUseClanBoost(UUID player) {
                return true;
            }

            @Override
            public String clanName(UUID clan) {
                return "§6Alpha";
            }

            @Override
            public Collection<UUID> onlineMembers(UUID clan) {
                return List.of(); // no server in unit tests: nobody to refresh or tell
            }
        });
        mining = cfg.type("mining");
        quest = cfg.type("quest");
    }

    private ClanBoostResult start(UUID clan, BoostType type, int tenths, long minutes, boolean confirmed, long now) {
        return controller.startClan(type, clan, tenths, minutes * 60, confirmed, "Steve", now);
    }

    // ------------------------------------------------------------------ E3.3 rules

    @Test
    void startThenSameMultiplierAddsTheDuration() {
        assertEquals(Status.STARTED, start(alpha, mining, 20, 5, false, NOW).status());
        assertEquals(NOW + 5 * MIN, registry.clan(alpha, "mining").expiresAt());
        // the spec example: x2 5 min + x2 10 min = x2 15 min, + x2 60 min = 1 h 15 min
        ClanBoostResult r = start(alpha, mining, 20, 10, false, NOW);
        assertEquals(Status.EXTENDED, r.status());
        assertEquals(NOW + 15 * MIN, registry.clan(alpha, "mining").expiresAt());
        assertEquals(Status.EXTENDED, start(alpha, mining, 20, 60, false, NOW).status());
        ClanBoost b = registry.clan(alpha, "mining");
        assertEquals(NOW + 75 * MIN, b.expiresAt());
        assertEquals(NOW, b.startedAt(), "an extension keeps the start");
        assertEquals(20, b.tenths());
    }

    @Test
    void extensionPastMaxDurationNeedsConfirmationAndIsCut() {
        long max = mining.maxDurationSeconds() * 1000L; // 24 h
        start(alpha, mining, 20, 23 * 60, false, NOW);
        ClanBoostResult warn = start(alpha, mining, 20, 120, false, NOW);
        assertEquals(Status.CONFIRM_CAPPED, warn.status());
        assertEquals(3600, warn.lostSeconds(), "23 h + 2 h = 25 h: 1 h is lost");
        assertEquals(NOW + 23 * 60 * MIN, registry.clan(alpha, "mining").expiresAt(), "nothing changed yet");
        ClanBoostResult done = start(alpha, mining, 20, 120, true, NOW);
        assertEquals(Status.EXTENDED, done.status());
        assertEquals(NOW + max, registry.clan(alpha, "mining").expiresAt());
        // already at max-duration: refused
        assertEquals(Status.FULL, start(alpha, mining, 20, 10, true, NOW).status());
        assertEquals(NOW + max, registry.clan(alpha, "mining").expiresAt());
    }

    @Test
    void higherReplacesAfterConfirmationLowerIsRefused() {
        start(alpha, mining, 20, 60, false, NOW);
        ClanBoostResult warn = start(alpha, mining, 30, 10, false, NOW + MIN);
        assertEquals(Status.CONFIRM_REPLACE, warn.status());
        assertEquals(20, warn.oldTenths());
        assertEquals(59 * 60, warn.lostSeconds(), "the remaining time of the old boost");
        assertEquals(20, registry.clan(alpha, "mining").tenths(), "nothing changed yet");
        ClanBoostResult done = start(alpha, mining, 30, 10, true, NOW + MIN);
        assertEquals(Status.REPLACED, done.status());
        ClanBoost b = registry.clan(alpha, "mining");
        assertEquals(30, b.tenths());
        assertEquals(NOW + 11 * MIN, b.expiresAt(), "the new boost's own duration, the old time is lost");
        ClanBoostResult lower = start(alpha, mining, 20, 60, true, NOW + MIN);
        assertEquals(Status.LOWER, lower.status());
        assertEquals(30, lower.oldTenths());
        assertEquals(30, registry.clan(alpha, "mining").tenths());
    }

    @Test
    void invalidOrUnavailableChangesNothing() {
        assertEquals(Status.INVALID, start(alpha, mining, 15, 10, true, NOW).status(), "mining is whole numbers only");
        assertEquals(Status.INVALID, start(alpha, mining, 20, 0, true, NOW).status());
        assertEquals(Status.INVALID, start(alpha, mining, 20, 25 * 60, true, NOW).status(), "above max-duration");
        assertEquals(Status.INVALID, start(alpha, mining, 20000, 10, true, NOW).status(), "above max-multiplier");
        assertEquals(Status.UNAVAILABLE, start(null, mining, 20, 10, true, NOW).status());
        assertNull(registry.clan(alpha, "mining"));
    }

    @Test
    void withoutProviderNothingStarts() {
        Plugin owner = mock(Plugin.class);
        link.set(owner, null);
        assertEquals(Status.UNAVAILABLE, start(alpha, mining, 20, 10, true, NOW).status());
        assertFalse(link.available());
    }

    @Test
    void decimalTypeAndSeparateClansAndTypes() {
        assertEquals(Status.STARTED, start(alpha, quest, 15, 10, false, NOW).status());
        assertEquals(Status.STARTED, start(alpha, mining, 20, 10, false, NOW).status());
        assertEquals(Status.STARTED, start(beta, mining, 50, 10, false, NOW).status());
        assertEquals(15, registry.clan(alpha, "quest").tenths());
        assertEquals(20, registry.clan(alpha, "mining").tenths());
        assertEquals(50, registry.clan(beta, "mining").tenths());
    }

    @Test
    void anExpiredBoostCountsAsNone() {
        start(alpha, mining, 30, 10, false, NOW);
        assertEquals(Status.STARTED, start(alpha, mining, 20, 10, false, NOW + 11 * MIN).status(),
                "the old x3 expired: a lower bottle starts");
    }

    @Test
    void everyChangeFiresAClanBoostEvent() {
        List<com.dex.boxcore.api.event.ClanBoostEvent> fired = new java.util.ArrayList<>();
        controller.events(e -> fired.add((com.dex.boxcore.api.event.ClanBoostEvent) e));
        start(alpha, mining, 20, 10, false, NOW);
        start(alpha, mining, 20, 10, false, NOW);
        start(alpha, mining, 30, 10, false, NOW);          // needs confirmation: nothing fired
        start(alpha, mining, 30, 10, true, NOW);
        start(alpha, mining, 20, 10, true, NOW);           // lower: refused, nothing fired
        controller.stopClan(alpha, "mining", NOW + MIN);
        start(alpha, quest, 15, 1, false, NOW);
        registry.expireClans(NOW); // nothing yet
        controller.tick(NOW + 2 * MIN);
        assertEquals(List.of("STARTED", "EXTENDED", "REPLACED", "STOPPED", "STARTED", "EXPIRED"),
                fired.stream().map(e -> e.action().name()).toList());
        assertEquals("Steve", fired.get(0).actor());
        assertEquals(600, fired.get(1).addedSeconds());
        assertEquals(20, fired.get(2).oldTenths());
        assertEquals(30, fired.get(2).tenths());
        assertEquals(null, fired.get(3).actor());
        assertEquals("quest", fired.get(5).type());
    }

    @Test
    void stopAndClear() {
        start(alpha, mining, 20, 10, false, NOW);
        assertTrue(controller.stopClan(alpha, "mining", NOW));
        assertFalse(controller.stopClan(alpha, "mining", NOW));
        start(alpha, mining, 20, 10, false, NOW);
        start(alpha, quest, 20, 10, false, NOW);
        assertTrue(registry.removeClan(alpha));
        assertNull(registry.clan(alpha, "mining"));
        assertNull(registry.clan(alpha, "quest"));
        assertFalse(registry.removeClan(alpha));
    }

    @Test
    void registryExpiresClanBoosts() {
        start(alpha, mining, 20, 10, false, NOW);
        start(beta, mining, 20, 30, false, NOW);
        assertTrue(registry.expireClans(NOW + 5 * MIN).isEmpty());
        List<ClanBoost> expired = registry.expireClans(NOW + 10 * MIN);
        assertEquals(1, expired.size());
        assertEquals(alpha, expired.get(0).clan());
        assertNull(registry.clan(alpha, "mining"));
        assertEquals(20, registry.clan(beta, "mining").tenths());
    }

    // ------------------------------------------------------------------ E3.1 effective multiplier

    private Player player(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        when(p.getEffectivePermissions()).thenReturn(Set.of());
        return p;
    }

    @Test
    void clanBoostIsIndependentLikeTheGlobalOne() {
        clanOf.put(steve, alpha);
        Player p = player(steve);
        start(alpha, mining, 20, 60, false, NOW);
        assertEquals(20, cache.recompute(p, cfg, registry, link, NOW).effective("mining"), "C alone");

        registry.setPersonal(steve, new PersonalBoost("mining", 30, NOW + 60 * MIN, 3600));
        assertEquals(30, cache.recompute(p, cfg, registry, link, NOW).effective("mining"), "max(B, C), never B + C");

        start(alpha, mining, 50, 10, true, NOW);
        EffectiveCache.Snapshot s = cache.recompute(p, cfg, registry, link, NOW);
        assertEquals(50, s.effective("mining"));
        assertEquals(50, s.clan("mining"));
        assertEquals(NOW + 10 * MIN, s.clanExpiresAt("mining"));
        assertEquals(alpha, s.clan());

        registry.putGlobal(new GlobalBoost("mining", 70, NOW, NOW + 10 * MIN));
        assertEquals(70, cache.recompute(p, cfg, registry, link, NOW).effective("mining"), "global higher wins");

        registry.putClan(new ClanBoost(alpha, "mining", 20000, NOW, NOW + 10 * MIN));
        assertEquals(mining.maxTenths(), cache.recompute(p, cfg, registry, link, NOW).effective("mining"), "capped");
    }

    @Test
    void joiningOrLeavingTheClanChangesTheMultiplierAtOnce() {
        Player p = player(steve);
        start(alpha, mining, 40, 60, false, NOW);
        assertEquals(10, cache.recompute(p, cfg, registry, link, NOW).effective("mining"), "not a member: x1");
        clanOf.put(steve, alpha);
        assertEquals(40, cache.recompute(p, cfg, registry, link, NOW).effective("mining"), "joined: x4");
        clanOf.put(steve, beta);
        assertEquals(10, cache.recompute(p, cfg, registry, link, NOW).effective("mining"), "another clan: x1");
        clanOf.remove(steve);
        EffectiveCache.Snapshot s = cache.recompute(p, cfg, registry, link, NOW);
        assertEquals(10, s.effective("mining"));
        assertNull(s.clan());
    }

    @Test
    void aBrokenProviderCountsAsNoClan() {
        link.set(mock(Plugin.class), new ClanProvider() {
            @Override
            public UUID clanOf(UUID player) {
                throw new IllegalStateException("broken");
            }

            @Override
            public boolean canUseClanBoost(UUID player) {
                throw new IllegalStateException("broken");
            }

            @Override
            public String clanName(UUID clan) {
                throw new IllegalStateException("broken");
            }

            @Override
            public Collection<UUID> onlineMembers(UUID clan) {
                throw new IllegalStateException("broken");
            }
        });
        registry.putClan(new ClanBoost(alpha, "mining", 40, NOW, NOW + MIN));
        assertEquals(10, cache.recompute(player(steve), cfg, registry, link, NOW).effective("mining"));
        assertFalse(link.canUse(steve));
        assertEquals("", link.clanName(alpha));
        assertTrue(link.onlineMembers(alpha).isEmpty());
    }

    @Test
    void providerIsRemovedWithItsPlugin() {
        Plugin clans = mock(Plugin.class);
        Plugin other = mock(Plugin.class);
        link.set(clans, mock(ClanProvider.class));
        assertFalse(link.removeIfOwner(other));
        assertTrue(link.available());
        assertTrue(link.removeIfOwner(clans));
        assertFalse(link.available());
        assertNull(link.clanOf(steve));
    }

    // ------------------------------------------------------------------ E3.2 API

    @Test
    void apiRunsOnTheMainThreadOnly() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        boolean[] main = {false};
        BoostServiceImpl api = new BoostServiceImpl(cache, () -> cfg, LOG, controller, null, queued::add, () -> main[0]);
        assertEquals(Status.UNAVAILABLE, api.startClanBoost(alpha, "mining", 20, 600, false, "Steve").status(),
                "off the main thread: refused, not queued");
        assertTrue(api.clanBoost(alpha, "mining").isEmpty());
        api.refreshClanOf(steve);
        assertEquals(1, queued.size(), "refreshClanOf is moved to the main thread");

        main[0] = true;
        assertEquals(Status.STARTED, api.startClanBoost(alpha, "mining", 20, 600, false, "Steve").status());
        assertEquals(Status.INVALID, api.startClanBoost(alpha, "nope", 20, 600, false, "Steve").status());
        assertEquals(20, api.clanBoost(alpha, "mining").orElseThrow().tenths());
        assertTrue(api.stopClanBoost(alpha, "mining"));
        assertTrue(api.clanBoost(alpha, "mining").isEmpty());
    }

    @Test
    void readOnlyServiceAnswersUnavailable() {
        BoostServiceImpl api = new BoostServiceImpl(cache, () -> cfg, LOG);
        assertEquals(Status.UNAVAILABLE, api.startClanBoost(alpha, "mining", 20, 600, false, null).status());
        assertFalse(api.stopClanBoost(alpha, "mining"));
        assertTrue(api.readBottle(null).isEmpty());
        api.registerClanProvider(mock(Plugin.class), mock(ClanProvider.class)); // no effect, no exception
        api.clearClan(alpha);
    }

    // ------------------------------------------------------------------ E5 extension maths

    @Test
    void extensionMaths() {
        long max = 24 * 60 * MIN;
        MultiplierCalculator.Extension e = MultiplierCalculator.extend(NOW + 5 * MIN, 10 * MIN, NOW, max);
        assertEquals(NOW + 15 * MIN, e.expiresAt());
        assertEquals(0, e.lostMs());
        assertFalse(e.full());
        e = MultiplierCalculator.extend(NOW + 23 * 60 * MIN, 2 * 60 * MIN, NOW, max);
        assertEquals(NOW + max, e.expiresAt());
        assertEquals(60 * MIN, e.lostMs());
        e = MultiplierCalculator.extend(NOW + max - 500, 10 * MIN, NOW, max);
        assertTrue(e.full(), "within a second of max-duration");
        e = MultiplierCalculator.extend(NOW + MIN, Long.MAX_VALUE, NOW, max);
        assertEquals(NOW + max, e.expiresAt(), "no overflow");
    }

    @Test
    void effectiveAndUsefulWithClan() {
        assertEquals(50, MultiplierCalculator.effective(20, 10, 40, 50, 1000));
        assertEquals(30, MultiplierCalculator.effective(10, 20, 0, 20, 1000), "R + B = x3 beats C = x2");
        assertEquals(1000, MultiplierCalculator.effective(0, 0, 0, 5000, 1000));
        assertEquals(MultiplierCalculator.effective(20, 30, 40, 1000), MultiplierCalculator.effective(20, 30, 40, 0, 1000),
                "C = 0 keeps the Update 01 rule");
        assertFalse(MultiplierCalculator.isUseful(0, 0, 0, 50, 30, 1000), "a x3 bottle under a x5 clan boost");
        assertTrue(MultiplierCalculator.isUseful(0, 0, 0, 20, 30, 1000));
        assertTrue(MultiplierCalculator.externalWins(0, 20, 0, 30));
        assertFalse(MultiplierCalculator.externalWins(20, 20, 30, 30));
    }
}
