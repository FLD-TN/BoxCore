package com.dex.boxcore.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionRefreshTaskTest {

    /** Every player is refreshed once per period: players per tick rounded up, never 0 when someone is online. */
    @Test
    void playersPerTick() {
        assertEquals(0, PermissionRefreshTask.perTick(0, 600));
        assertEquals(1, PermissionRefreshTask.perTick(1, 600));
        assertEquals(1, PermissionRefreshTask.perTick(600, 600));
        assertEquals(2, PermissionRefreshTask.perTick(601, 600));
        assertEquals(5, PermissionRefreshTask.perTick(500, 100));
        assertEquals(3, PermissionRefreshTask.perTick(3, 1));
    }
}
