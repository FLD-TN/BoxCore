package com.dex.boxcore.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatTest {

    private static final DurationFormat.Units VI = DurationFormat.Units.DEFAULT;

    @Test
    void parsesSingleAndCombinedGroups() {
        assertEquals(30, DurationFormat.parse("30s"));
        assertEquals(600, DurationFormat.parse("10m"));
        assertEquals(5400, DurationFormat.parse("1h30m"));
        assertEquals(172800, DurationFormat.parse("2d"));
        assertEquals(86400 + 3600 + 60 + 1, DurationFormat.parse("1d1h1m1s"));
        assertEquals(3600, DurationFormat.parse("1H"));
    }

    @Test
    void rejectsInvalidInput() {
        assertEquals(-1, DurationFormat.parse("0m"));
        assertEquals(-1, DurationFormat.parse("abc"));
        assertEquals(-1, DurationFormat.parse(""));
        assertEquals(-1, DurationFormat.parse(null));
        assertEquals(-1, DurationFormat.parse("10"));
        assertEquals(-1, DurationFormat.parse("m"));
        assertEquals(-1, DurationFormat.parse("1.5h"));
        assertEquals(-1, DurationFormat.parse("-5m"));
        assertEquals(-1, DurationFormat.parse("10x"));
        assertEquals(-1, DurationFormat.parse("99999999999999999999d"));
    }

    @Test
    void parsesLargeButCallerEnforcesMax() {
        // 99d parses; the command rejects it against the type's max-duration
        assertEquals(99L * 86400, DurationFormat.parse("99d"));
    }

    @Test
    void formatsTwoLargestNonZeroUnits() {
        assertEquals("20 phút", DurationFormat.format(1200, VI));
        assertEquals("1 giờ 30 phút", DurationFormat.format(5400, VI));
        assertEquals("2 ngày 3 giờ", DurationFormat.format(2 * 86400 + 3 * 3600 + 59, VI));
        assertEquals("45 giây", DurationFormat.format(45, VI));
        assertEquals("1 giờ", DurationFormat.format(3600, VI));
        assertEquals("1 giờ 5 giây", DurationFormat.format(3605, VI));
        assertEquals("1 ngày", DurationFormat.format(86400, VI));
    }

    @Test
    void clockFormat() {
        assertEquals("00:00", DurationFormat.clock(0));
        assertEquals("00:45", DurationFormat.clock(45));
        assertEquals("59:59", DurationFormat.clock(3599));
        assertEquals("01:00:00", DurationFormat.clock(3600));
        assertEquals("24:00:00", DurationFormat.clock(86400));
    }

    @Test
    void remainingSecondsRoundsUp() {
        assertEquals(1, DurationFormat.remainingSeconds(1001, 1000));
        assertEquals(1, DurationFormat.remainingSeconds(2000, 1000));
        assertEquals(2, DurationFormat.remainingSeconds(2001, 1000));
        assertEquals(0, DurationFormat.remainingSeconds(1000, 1000));
        assertEquals(0, DurationFormat.remainingSeconds(500, 1000));
    }
}
