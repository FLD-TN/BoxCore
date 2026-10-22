package com.dex.boxcore.boost;

import com.dex.boxcore.config.BoostType;
import net.kyori.adventure.bossbar.BossBar;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Values in tenths (Update 04 D1). mining: whole numbers, max x100; quest: decimal, max x10. */
class PermissionReaderTest {

    private static final Map<String, BoostType> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("mining", new BoostType("mining", 0, "Mining", "", 1000, false, 86400, null, BossBar.Color.YELLOW));
        TYPES.put("quest", new BoostType("quest", 1, "Quest", "", 100, true, 86400, null, BossBar.Color.BLUE));
    }

    @Test
    void noPermissionIsZero() {
        assertArrayEquals(new int[]{0, 0}, PermissionReader.read(List.of("essentials.fly"), TYPES));
    }

    @Test
    void valueOneCountsAsNoRank() {
        int[] rank = PermissionReader.read(List.of("boxcore.boost.mining.1"), TYPES);
        assertArrayEquals(new int[]{0, 0}, rank);
        // with bottle x5 -> x5, not x6
        assertEquals(50, MultiplierCalculator.effective(rank[0], 50, 0, 1000));
        assertArrayEquals(new int[]{0, 0}, PermissionReader.read(List.of(
                "boxcore.boost.quest.1", "boxcore.boost.quest.1_0", "boxcore.boost.quest.0_5"), TYPES));
    }

    @Test
    void largestValueWins() {
        assertArrayEquals(new int[]{70, 30}, PermissionReader.read(List.of(
                "boxcore.boost.mining.2", "boxcore.boost.mining.7", "boxcore.boost.mining.5",
                "boxcore.boost.mining.1", "boxcore.boost.quest.3"), TYPES));
        assertArrayEquals(new int[]{0, 35}, PermissionReader.read(List.of(
                "boxcore.boost.quest.3", "boxcore.boost.quest.3_5", "boxcore.boost.quest.1_5"), TYPES));
    }

    @Test
    void decimalValuesForDecimalTypes() {
        assertArrayEquals(new int[]{0, 15}, PermissionReader.read(List.of("boxcore.boost.quest.1_5"), TYPES));
        assertArrayEquals(new int[]{0, 11}, PermissionReader.read(List.of("boxcore.boost.quest.1_1"), TYPES));
        assertArrayEquals(new int[]{0, 30}, PermissionReader.read(List.of("boxcore.boost.quest.3_0"), TYPES));
        assertArrayEquals(new int[]{0, 75}, PermissionReader.read(List.of("boxcore.boost.quest.007_5"), TYPES));
    }

    @Test
    void decimalValuesIgnoredForWholeTypes() {
        assertArrayEquals(new int[]{0, 0}, PermissionReader.read(List.of("boxcore.boost.mining.1_5"), TYPES));
        assertArrayEquals(new int[]{30, 0}, PermissionReader.read(List.of(
                "boxcore.boost.mining.3_0", "boxcore.boost.mining.2_5"), TYPES));
    }

    @Test
    void clampedToMaxMultiplier() {
        assertArrayEquals(new int[]{1000, 100}, PermissionReader.read(List.of(
                "boxcore.boost.mining.500", "boxcore.boost.quest.99999999999999"), TYPES));
        assertArrayEquals(new int[]{0, 100}, PermissionReader.read(List.of("boxcore.boost.quest.12_5"), TYPES));
    }

    @Test
    void invalidValuesIgnored() {
        assertArrayEquals(new int[]{0, 0}, PermissionReader.read(List.of(
                "boxcore.boost.mining.0", "boxcore.boost.mining.-3", "boxcore.boost.mining.2.5",
                "boxcore.boost.mining.abc", "boxcore.boost.mining.", "boxcore.boost.mining",
                "boxcore.boost.mining.*", "boxcore.boost.fishing.5", "boxcore.boost.", "boxcore.boost.5"), TYPES));
        assertArrayEquals(new int[]{0, 0}, PermissionReader.read(List.of(
                "boxcore.boost.quest.1_25", "boxcore.boost.quest.1_", "boxcore.boost.quest._5",
                "boxcore.boost.quest.1.5", "boxcore.boost.quest.1_a", "boxcore.boost.quest.1_5_5",
                "boxcore.boost.quest.1,5"), TYPES));
    }

    @Test
    void caseInsensitiveNodes() {
        assertArrayEquals(new int[]{40, 0}, PermissionReader.read(List.of("BoxCore.Boost.Mining.4"), TYPES));
    }
}
