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

/** All values are in tenths (Update 04 D1): 10 = x1, 15 = x1.5. */
class MultiplierCalculatorTest {

    private static final String MINING = "mining";
    private static final long NOW = 1_000_000L;
    private static final int MAX = 1000;

    /** The owner's reference table of Update 01 U1 (0 = absent source), in tenths. */
    @ParameterizedTest(name = "rank {0} + bottle {1}, global {2} -> {3}")
    @CsvSource({
            "0,   0,   0,   10",
            "20,  0,   0,   20",
            "0,   50,  0,   50",
            "20,  50,  0,   70",
            "50,  20,  0,   70",
            "20,  50,  100, 100",
            "20,  50,  40,  70",
            "600, 600, 0,   1000",
    })
    void referenceTable(int rank, int bottle, int global, int expected) {
        assertEquals(expected, MultiplierCalculator.effective(rank, bottle, global, MAX));
    }

    /** Update 04 D2: decimal reference cases (quest). */
    @ParameterizedTest(name = "rank {0} + bottle {1}, global {2} -> {3}")
    @CsvSource({
            "15,  0,   0,   15",
            "0,   25,  0,   25",
            "15,  20,  0,   35",
            "15,  20,  30,  35",
            "15,  20,  45,  45",
            "605, 600, 0,   1000",
    })
    void decimalReferenceTable(int rank, int bottle, int global, int expected) {
        assertEquals(expected, MultiplierCalculator.effective(rank, bottle, global, MAX));
    }

    @Test
    void globalNeverAdds() {
        assertEquals(100, MultiplierCalculator.effective(20, 50, 100, MAX));
        assertEquals(70, MultiplierCalculator.effective(20, 50, 70, MAX));
        assertTrue(MultiplierCalculator.globalWins(20, 50, 100));
        assertFalse(MultiplierCalculator.globalWins(20, 50, 70));
        assertFalse(MultiplierCalculator.globalWins(0, 0, 0));
    }

    @Test
    void capAppliesToFinalValue() {
        assertEquals(1000, MultiplierCalculator.effective(600, 600, 0, 1000));
        assertEquals(100, MultiplierCalculator.effective(0, 0, 500, 100)); // global above a lowered max
        assertEquals(10, MultiplierCalculator.effective(0, 0, 0, 1000));
        assertEquals(25, MultiplierCalculator.effective(20, 15, 0, 25)); // decimal cap
    }

    @Test
    void personalIsSumOrOne() {
        assertEquals(10, MultiplierCalculator.personal(0, 0));
        assertEquals(70, MultiplierCalculator.personal(20, 50));
        assertEquals(50, MultiplierCalculator.personal(0, 50));
        assertEquals(35, MultiplierCalculator.personal(15, 20));
    }

    // ------------------------------------------------------------------ U2 / U7 bottle use decisions

    @Test
    void lowerBottleThanActiveIsNoEffect() {
        // Rank x5, bottle x3 active, use x2 -> warning
        assertFalse(MultiplierCalculator.isUseful(50, 30, 0, 20, MAX));
        // Rank x1.5, bottle x2.5 active, use x2 -> warning
        assertFalse(MultiplierCalculator.isUseful(15, 25, 0, 20, MAX));
    }

    @Test
    void higherBottleReplacesActive() {
        // Rank x5, bottle x2 active, use x3 -> stored, effective 8
        assertTrue(MultiplierCalculator.isUseful(50, 20, 0, 30, MAX));
        assertEquals(80, MultiplierCalculator.effective(50, 30, 0, MAX));
        // bottle x2 active, use x2.5 -> stored
        assertTrue(MultiplierCalculator.isUseful(0, 20, 0, 25, MAX));
    }

    @Test
    void sameMultiplierRenewsActive() {
        assertFalse(MultiplierCalculator.isUseful(0, 50, 0, 50, MAX));
        assertTrue(MultiplierCalculator.isRenewal(50, 50));
        assertTrue(MultiplierCalculator.isRenewal(15, 15));
        // even when a global boost determines the value
        assertTrue(MultiplierCalculator.isRenewal(100, 100));
        assertFalse(MultiplierCalculator.isRenewal(100, 90));
        assertFalse(MultiplierCalculator.isRenewal(20, 25));
        assertFalse(MultiplierCalculator.isRenewal(0, 0));
    }

    @Test
    void globalAtLeastRankPlusBottleIsNoEffect() {
        // Global x10, rank x2, bottle x5 -> after 10 = current 10 -> warning
        assertFalse(MultiplierCalculator.isUseful(20, 0, 100, 50, MAX));
        // Global x10, rank x2, bottle x9 -> after 11 > 10 -> stored, no "dang dung" suffix
        assertTrue(MultiplierCalculator.isUseful(20, 0, 100, 90, MAX));
        assertEquals(110, MultiplierCalculator.effective(20, 90, 100, MAX));
        assertFalse(MultiplierCalculator.globalWins(20, 90, 100));
        // Global x3, rank x1.5, bottle x1.5 -> after 3 = current 3 -> warning
        assertFalse(MultiplierCalculator.isUseful(15, 0, 30, 15, MAX));
    }

    @Test
    void bottleWithoutRankOrGlobal() {
        assertTrue(MultiplierCalculator.isUseful(0, 0, 0, 20, MAX));
        assertTrue(MultiplierCalculator.isUseful(0, 0, 0, 11, MAX));
    }

    @Test
    void cappedValueCannotGrow() {
        // rank x60 + bottle x50 = capped 100; a bottle x60 would still give 100 -> no effect
        assertFalse(MultiplierCalculator.isUseful(600, 500, 0, 600, MAX));
    }

    // ------------------------------------------------------------------ sources from boosts

    private static PersonalBoost personal(int m, long remainingMs) {
        return new PersonalBoost(MINING, m, NOW + remainingMs, remainingMs / 1000);
    }

    @Test
    void bottleAndGlobalSourceValues() {
        assertEquals(0, MultiplierCalculator.bottle(List.of(), MINING, NOW));
        assertEquals(60, MultiplierCalculator.bottle(List.of(personal(60, 60_000)), MINING, NOW));
        assertEquals(15, MultiplierCalculator.bottle(List.of(personal(15, 60_000)), MINING, NOW));
        assertEquals(0, MultiplierCalculator.bottle(List.of(personal(60, 0)), MINING, NOW));
        assertEquals(0, MultiplierCalculator.bottle(List.of(new PersonalBoost("quest", 90, NOW + 1000, 1)), MINING, NOW));
        assertEquals(0, MultiplierCalculator.global(null, NOW));
        assertEquals(0, MultiplierCalculator.global(new GlobalBoost(MINING, 80, NOW - 1000, NOW), NOW));
        assertEquals(80, MultiplierCalculator.global(new GlobalBoost(MINING, 80, NOW - 1000, NOW + 1), NOW));
    }

    @Test
    void displayedIsHighestThenLongest() {
        PersonalBoost a = personal(50, 60_000);
        PersonalBoost b = personal(50, 120_000);
        PersonalBoost c = personal(30, 999_000);
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
