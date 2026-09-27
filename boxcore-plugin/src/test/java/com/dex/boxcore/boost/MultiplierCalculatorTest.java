package com.dex.boxcore.boost;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplierCalculatorTest {

    private static final String MINING = "mining";
    private static final long NOW = 1_000_000L;
    private static final int MAX = 100;

    /** The owner's reference table of Update 01 U1 (0 = absent source). */
    @ParameterizedTest(name = "rank {0} + bottle {1}, global {2} -> x{3}")
    @CsvSource({
            "0,  0,  0,  1",
            "2,  0,  0,  2",
            "0,  5,  0,  5",
            "2,  5,  0,  7",
            "5,  2,  0,  7",
            "2,  5,  10, 10",
            "2,  5,  4,  7",
            "60, 60, 0,  100",
    })
    void referenceTable(int rank, int bottle, int global, int expected) {
        assertEquals(expected, MultiplierCalculator.effective(rank, bottle, global, MAX));
    }

    @Test
    void globalNeverAdds() {
        assertEquals(10, MultiplierCalculator.effective(2, 5, 10, MAX));
        assertEquals(7, MultiplierCalculator.effective(2, 5, 7, MAX));
        assertTrue(MultiplierCalculator.globalWins(2, 5, 10));
        assertFalse(MultiplierCalculator.globalWins(2, 5, 7));
        assertFalse(MultiplierCalculator.globalWins(0, 0, 0));
    }

    @Test
    void capAppliesToFinalValue() {
        assertEquals(100, MultiplierCalculator.effective(60, 60, 0, 100));
        assertEquals(10, MultiplierCalculator.effective(0, 0, 50, 10)); // global above a lowered max
        assertEquals(1, MultiplierCalculator.effective(0, 0, 0, 100));
    }

    @Test
    void personalIsSumOrOne() {
        assertEquals(1, MultiplierCalculator.personal(0, 0));
        assertEquals(7, MultiplierCalculator.personal(2, 5));
        assertEquals(5, MultiplierCalculator.personal(0, 5));
    }

    // ------------------------------------------------------------------ U2 / U7 bottle use decisions

    @Test
    void lowerBottleThanActiveIsNoEffect() {
        // Rank x5, bottle x3 active, use x2 -> warning
        assertFalse(MultiplierCalculator.isUseful(5, 3, 0, 2, MAX));
    }

    @Test
    void higherBottleReplacesActive() {
        // Rank x5, bottle x2 active, use x3 -> stored, effective 8
        assertTrue(MultiplierCalculator.isUseful(5, 2, 0, 3, MAX));
        assertEquals(8, MultiplierCalculator.effective(5, 3, 0, MAX));
    }

    @Test
    void sameMultiplierIsNoEffect() {
        assertFalse(MultiplierCalculator.isUseful(0, 5, 0, 5, MAX));
    }

    @Test
    void globalAtLeastRankPlusBottleIsNoEffect() {
        // Global x10, rank x2, bottle x5 -> after 10 = current 10 -> warning
        assertFalse(MultiplierCalculator.isUseful(2, 0, 10, 5, MAX));
        // Global x10, rank x2, bottle x9 -> after 11 > 10 -> stored, no "dang dung" suffix
        assertTrue(MultiplierCalculator.isUseful(2, 0, 10, 9, MAX));
        assertEquals(11, MultiplierCalculator.effective(2, 9, 10, MAX));
        assertFalse(MultiplierCalculator.globalWins(2, 9, 10));
    }

    @Test
    void bottleWithoutRankOrGlobal() {
        assertTrue(MultiplierCalculator.isUseful(0, 0, 0, 2, MAX));
    }

    @Test
    void cappedValueCannotGrow() {
        // rank x60 + bottle x50 = capped 100; a bottle x60 would still give 100 -> no effect
        assertFalse(MultiplierCalculator.isUseful(60, 50, 0, 60, MAX));
    }

    // ------------------------------------------------------------------ sources from boosts

    private static PersonalBoost personal(int m, long remainingMs) {
        return new PersonalBoost(MINING, m, NOW + remainingMs, remainingMs / 1000);
    }

    @Test
    void bottleAndGlobalSourceValues() {
        assertEquals(0, MultiplierCalculator.bottle(List.of(), MINING, NOW));
        assertEquals(6, MultiplierCalculator.bottle(List.of(personal(6, 60_000)), MINING, NOW));
        assertEquals(0, MultiplierCalculator.bottle(List.of(personal(6, 0)), MINING, NOW));
        assertEquals(0, MultiplierCalculator.bottle(List.of(new PersonalBoost("quest", 9, NOW + 1000, 1)), MINING, NOW));
        assertEquals(0, MultiplierCalculator.global(null, NOW));
        assertEquals(0, MultiplierCalculator.global(new GlobalBoost(MINING, 8, NOW - 1000, NOW), NOW));
        assertEquals(8, MultiplierCalculator.global(new GlobalBoost(MINING, 8, NOW - 1000, NOW + 1), NOW));
    }

    @Test
    void displayedIsHighestThenLongest() {
        PersonalBoost a = personal(5, 60_000);
        PersonalBoost b = personal(5, 120_000);
        PersonalBoost c = personal(3, 999_000);
        assertSame(b, MultiplierCalculator.displayed(List.of(a, b, c), MINING, NOW));
        assertSame(c, MultiplierCalculator.displayed(List.of(a, b, c), MINING, NOW + 200_000));
        assertNull(MultiplierCalculator.displayed(List.of(), MINING, NOW));
    }

    @Test
    void progressIsClamped() {
        assertEquals(0f, MultiplierCalculator.progress(-5, 100));
        assertEquals(1f, MultiplierCalculator.progress(150, 100));
        assertEquals(0.5f, MultiplierCalculator.progress(50, 100));
        assertEquals(0f, MultiplierCalculator.progress(50, 0));
    }
}
