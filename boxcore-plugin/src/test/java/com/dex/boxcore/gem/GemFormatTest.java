package com.dex.boxcore.gem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GemFormatTest {

    @Test
    void grouped() {
        assertEquals("0", GemFormat.grouped(0));
        assertEquals("999", GemFormat.grouped(999));
        assertEquals("1.000", GemFormat.grouped(1000));
        assertEquals("1.250", GemFormat.grouped(1250));
        assertEquals("12.345.678", GemFormat.grouped(12_345_678));
        assertEquals("1.000.000.000.000", GemFormat.grouped(1_000_000_000_000L));
        assertEquals("-20", GemFormat.grouped(-20));
        assertEquals("-1.500", GemFormat.grouped(-1500));
    }

    @Test
    void shortForm() {
        assertEquals("0", GemFormat.shortForm(0));
        assertEquals("999", GemFormat.shortForm(999));
        assertEquals("1,0K", GemFormat.shortForm(1000));
        assertEquals("1,2K", GemFormat.shortForm(1299));        // rounded down
        assertEquals("999,9K", GemFormat.shortForm(999_999));
        assertEquals("1,0M", GemFormat.shortForm(1_000_000));
        assertEquals("3,4M", GemFormat.shortForm(3_456_789));
        assertEquals("5,6B", GemFormat.shortForm(5_600_000_000L));
        assertEquals("1,0T", GemFormat.shortForm(1_000_000_000_000L));
    }

    @Test
    void parseAmountAcceptsDigitsOnly() {
        assertEquals(0, GemFormat.parseAmount("0"));
        assertEquals(50, GemFormat.parseAmount("50"));
        assertEquals(1_000_000_000_000L, GemFormat.parseAmount("1000000000000"));
        for (String bad : new String[]{null, "", "-5", "+5", "1k", "1.5", "1,5", "1.000", " 5", "abc", "99999999999999"}) {
            assertEquals(-1, GemFormat.parseAmount(bad), String.valueOf(bad));
        }
    }

    @Test
    void sanitizeReason() {
        assertEquals("quest:mine-t3", GemFormat.sanitizeReason("quest:mine-t3"));
        assertEquals("admin:dex:give", GemFormat.sanitizeReason("admin:Dex:give"));
        assertEquals("shopkeeper:tier_shop/t1", GemFormat.sanitizeReason("shopkeeper:tier_shop/t1"));
        assertEquals("a_b_c", GemFormat.sanitizeReason("a b@c"));
        assertEquals(64, GemFormat.sanitizeReason("x".repeat(100)).length());
        assertNull(GemFormat.sanitizeReason(null));
        assertNull(GemFormat.sanitizeReason("   "));
    }

    @Test
    void signed() {
        assertEquals("+50", GemFormat.signed(50));
        assertEquals("-1.200", GemFormat.signed(-1200));
        assertEquals("0", GemFormat.signed(0));
    }
}
