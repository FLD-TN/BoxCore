package com.dex.boxcore.boost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dex.boxcore.util.ErrorReporter;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** BoxCore 1.6.0: bonus P adds on top of the winning source, capped. */
class BonusTest {

    @Test
    void bonusAddsOnTopOfWinningSource() {
        assertEquals(15, MultiplierCalculator.effective(0, 0, 0, 0, 5, 1000), "x1 + 0.5");
        assertEquals(25, MultiplierCalculator.effective(20, 0, 0, 0, 5, 1000), "rank x2 + 0.5");
        assertEquals(35, MultiplierCalculator.effective(10, 10, 30, 0, 5, 1000), "global x3 wins, + 0.5");
        assertEquals(45, MultiplierCalculator.effective(0, 0, 30, 40, 5, 1000), "clan x4 wins, + 0.5");
        assertEquals(50, MultiplierCalculator.effective(40, 0, 0, 0, 20, 50), "capped at max");
        assertEquals(20, MultiplierCalculator.effective(20, 0, 0, 0, -5, 1000), "negative bonus = 0");
        assertEquals(20, MultiplierCalculator.effective(20, 0, 0, 0, 1000), "old overload = no bonus");
    }

    @Test
    void linkSumsProvidersAndSurvivesErrors() {
        BonusLink link = new BonusLink(new ErrorReporter(Logger.getLogger("test")));
        Plugin a = Mockito.mock(Plugin.class);
        Plugin b = Mockito.mock(Plugin.class);
        Mockito.when(b.getName()).thenReturn("Broken");
        UUID p = UUID.randomUUID();
        assertFalse(link.available());
        link.set(a, (id, type) -> type.equals("mining") ? 10 : 3);
        link.set(b, (id, type) -> { throw new IllegalStateException("boom"); });
        assertTrue(link.available());
        assertEquals(10, link.bonus(p, "mining"));
        assertEquals(3, link.bonus(p, "quest"));
        assertTrue(link.removeIfOwner(a));
        assertEquals(0, link.bonus(p, "mining"));
        assertFalse(link.removeIfOwner(a));
    }
}
