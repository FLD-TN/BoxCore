package com.dex.boxcore.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultipliersTest {

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
            "2|20", "2.0|20", "1.5|15", "'1,5'|15", "007|70", " 2.5 |25", "0|0", "99999.9|999999", "12.5|125",
    })
    void parsesValid(String raw, int expected) {
        assertEquals(expected, Multipliers.parse(raw));
    }

    @ParameterizedTest(name = "\"{0}\" is invalid")
    @ValueSource(strings = {"", " ", "1.25", "2.50", ".5", "5.", "-1", "+1", "1 .5", "1. 5", "1e3", "123456",
            "abc", "1.a", "1..5", "1_5", "x2"})
    void rejectsInvalid(String raw) {
        assertEquals(-1, Multipliers.parse(raw));
    }

    @Test
    void parsesNull() {
        assertEquals(-1, Multipliers.parse(null));
    }

    @Test
    void formats() {
        assertEquals("2", Multipliers.format(20));
        assertEquals("1.5", Multipliers.format(15));
        assertEquals("100.5", Multipliers.format(1005));
        assertEquals("1", Multipliers.format(10));
        assertEquals("0", Multipliers.format(0));
        assertEquals("1,5", Multipliers.format(15, ','));
        assertEquals("100", Multipliers.format(1000, ','));
    }

    @Test
    void halfUpAndFloor() {
        assertEquals(8, Multipliers.halfUp(5, 15));   // 7.5
        assertEquals(5, Multipliers.halfUp(3, 15));   // 4.5
        assertEquals(3, Multipliers.halfUp(2, 15));   // 3.0
        assertEquals(4, Multipliers.halfUp(3, 13));   // 3.9
        assertEquals(3, Multipliers.halfUp(3, 11));   // 3.3
        assertEquals(4, Multipliers.floor(3, 15));    // 4.5
        assertEquals(6, Multipliers.floor(2, 30));
        assertEquals(3750, Multipliers.halfUp(2500, 15));
    }

    @Test
    void noBoostAndNonPositiveAmountsUnchanged() {
        assertEquals(7, Multipliers.halfUp(7, 10));
        assertEquals(7, Multipliers.floor(7, 10));
        assertEquals(7, Multipliers.floor(7, 0));
        assertEquals(0, Multipliers.halfUp(0, 15));
        assertEquals(-3, Multipliers.floor(-3, 15));
    }

    @Test
    void saturates() {
        assertEquals(Long.MAX_VALUE, Multipliers.floor(Long.MAX_VALUE, 15));
        assertEquals(Long.MAX_VALUE, Multipliers.halfUp(Long.MAX_VALUE / 2, 30));
        assertEquals(1_000_000_000_000L * 100_000 / 10, Multipliers.halfUp(1_000_000_000_000L, 100_000));
    }
}
