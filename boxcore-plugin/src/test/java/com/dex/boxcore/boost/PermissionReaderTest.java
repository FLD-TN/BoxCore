package com.dex.boxcore.boost;

import com.dex.boxcore.config.BoostType;
import net.kyori.adventure.bossbar.BossBar;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionReaderTest {

    private static final Map<String, BoostType> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("mining", new BoostType("mining", 0, "Mining", "", 100, 86400, null, BossBar.Color.YELLOW));
        TYPES.put("quest", new BoostType("quest", 1, "Quest", "", 10, 86400, null, BossBar.Color.BLUE));
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
        assertEquals(5, MultiplierCalculator.effective(rank[0], 5, 0, 100));
    }

    @Test
    void largestValueWins() {
        assertArrayEquals(new int[]{7, 3}, PermissionReader.read(List.of(
                "boxcore.boost.mining.2", "boxcore.boost.mining.7", "boxcore.boost.mining.5",
                "boxcore.boost.mining.1", "boxcore.boost.quest.3"), TYPES));
    }

    @Test
    void clampedToMaxMultiplier() {
        assertArrayEquals(new int[]{100, 10}, PermissionReader.read(List.of(
                "boxcore.boost.mining.500", "boxcore.boost.quest.99999999999999"), TYPES));
    }

    @Test
    void invalidValuesIgnored() {
        assertArrayEquals(new int[]{0, 0}, PermissionReader.read(List.of(
                "boxcore.boost.mining.0", "boxcore.boost.mining.-3", "boxcore.boost.mining.2.5",
                "boxcore.boost.mining.abc", "boxcore.boost.mining.", "boxcore.boost.mining",
                "boxcore.boost.mining.*", "boxcore.boost.fishing.5", "boxcore.boost.", "boxcore.boost.5"), TYPES));
    }

    @Test
    void caseInsensitiveNodes() {
        assertArrayEquals(new int[]{4, 0}, PermissionReader.read(List.of("BoxCore.Boost.Mining.4"), TYPES));
    }
}
