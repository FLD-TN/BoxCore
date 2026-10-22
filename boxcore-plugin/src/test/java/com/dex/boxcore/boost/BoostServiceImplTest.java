package com.dex.boxcore.boost;

import com.dex.boxcore.api.BoostService;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BoostServiceImplTest {

    private static BoostService fixed(int m) {
        return new BoostService() {
            @Override
            public int getMultiplier(UUID playerId, String type) {
                return m;
            }

            @Override
            public boolean isType(String type) {
                return true;
            }
        };
    }

    @Test
    void multiplySaturates() {
        assertEquals(Long.MAX_VALUE, fixed(5).multiply(UUID.randomUUID(), "mining", Long.MAX_VALUE / 2));
        assertEquals(Long.MAX_VALUE, fixed(2).multiply(UUID.randomUUID(), "mining", Long.MAX_VALUE));
        assertEquals(50, fixed(5).multiply(UUID.randomUUID(), "mining", 10));
        assertEquals(10, fixed(1).multiply(UUID.randomUUID(), "mining", 10));
    }

    @Test
    void multiplyKeepsNonPositiveAmounts() {
        assertEquals(0, fixed(5).multiply(UUID.randomUUID(), "mining", 0));
        assertEquals(-3, fixed(5).multiply(UUID.randomUUID(), "mining", -3));
    }

    @Test
    void unknownOfflineAndNullReturnOne() {
        BoostServiceImpl service = new BoostServiceImpl(new EffectiveCache(), () -> null, Logger.getLogger("test"));
        assertEquals(1, service.getMultiplier(UUID.randomUUID(), "mining"));
        assertEquals(1, service.getMultiplier(null, "mining"));
        assertEquals(1, service.getMultiplier(UUID.randomUUID(), null));
        assertEquals(1, service.getMultiplier(null, null));
        assertEquals(7, service.multiply(null, null, 7));
        assertEquals(10, service.getMultiplierTenths(UUID.randomUUID(), "quest"));
        assertEquals(10, service.getMultiplierTenths(null, null));
        assertEquals(1.0, service.getMultiplierExact(null, "quest"));
        // config supplier returns null: must not throw
        assertFalse(service.isType("mining"));
        assertFalse(service.isType(null));
    }

    /** A consumer's view of an implementation built against API 1.1.0: only getMultiplier is implemented. */
    @Test
    void defaultTenthsMethodsUseGetMultiplier() {
        assertEquals(30, fixed(3).getMultiplierTenths(UUID.randomUUID(), "mining"));
        assertEquals(3.0, fixed(3).getMultiplierExact(UUID.randomUUID(), "mining"));
    }

    @Test
    void decimalMultiplierWholePartAndFloorMultiply() {
        BoostService service = new BoostService() {
            @Override
            public int getMultiplier(UUID playerId, String type) {
                return getMultiplierTenths(playerId, type) / 10;
            }

            @Override
            public int getMultiplierTenths(UUID playerId, String type) {
                return 15;
            }

            @Override
            public boolean isType(String type) {
                return true;
            }
        };
        assertEquals(1, service.getMultiplier(UUID.randomUUID(), "quest"));
        assertEquals(1.5, service.getMultiplierExact(UUID.randomUUID(), "quest"));
        assertEquals(4, service.multiply(UUID.randomUUID(), "quest", 3)); // floor(4.5)
        assertEquals(15, service.multiply(UUID.randomUUID(), "quest", 10));
    }
}
