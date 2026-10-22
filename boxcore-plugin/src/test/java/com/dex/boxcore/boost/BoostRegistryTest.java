package com.dex.boxcore.boost;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoostRegistryTest {

    private static final long NOW = 10_000_000L;
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void atMostOneBottlePerType() {
        BoostRegistry registry = new BoostRegistry();
        AtomicInteger changes = new AtomicInteger();
        registry.onChange(changes::incrementAndGet);

        PersonalBoost x2 = new PersonalBoost("mining", 20, NOW + 20 * 60_000, 1200);
        PersonalBoost quest = new PersonalBoost("quest", 40, NOW + 60_000, 60);
        PersonalBoost x3 = new PersonalBoost("mining", 30, NOW + 5 * 60_000, 300);

        assertNull(registry.setPersonal(PLAYER, x2));
        assertNull(registry.setPersonal(PLAYER, quest));
        assertSame(x2, registry.setPersonal(PLAYER, x3));
        assertEquals(List.of(quest, x3), registry.personal(PLAYER));
        assertEquals(3, changes.get());
    }

    @Test
    void afterReplacementExpiryFallsBackToRankOnly() {
        BoostRegistry registry = new BoostRegistry();
        registry.setPersonal(PLAYER, new PersonalBoost("mining", 20, NOW + 20 * 60_000, 1200));
        registry.setPersonal(PLAYER, new PersonalBoost("mining", 30, NOW + 5 * 60_000, 300));

        long later = NOW + 5 * 60_000;
        Map<UUID, List<PersonalBoost>> expired = registry.expirePersonal(later);
        assertEquals(1, expired.get(PLAYER).size());
        assertTrue(registry.personal(PLAYER).isEmpty());
        // the old x2 is gone: rank x5 alone
        assertEquals(50, MultiplierCalculator.effective(50,
                MultiplierCalculator.bottle(registry.personal(PLAYER), "mining", later), 0, 1000));
    }

    @Test
    void loadKeepsOneBottlePerTypeFromLegacyData() {
        BoostRegistry registry = new BoostRegistry();
        PersonalBoost high = new PersonalBoost("mining", 60, NOW + 60_000, 60);
        PersonalBoost low = new PersonalBoost("mining", 30, NOW + 600_000, 600);
        PersonalBoost sameShort = new PersonalBoost("quest", 40, NOW + 60_000, 60);
        PersonalBoost sameLong = new PersonalBoost("quest", 40, NOW + 120_000, 120);
        int dropped = registry.load(Map.of(), Map.of(PLAYER, List.of(low, high, sameShort, sameLong)));
        assertEquals(2, dropped);
        assertEquals(List.of(high, sameLong), registry.personal(PLAYER));
    }
}
