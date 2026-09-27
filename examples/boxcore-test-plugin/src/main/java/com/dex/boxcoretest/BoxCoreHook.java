package com.dex.boxcoretest;

import com.dex.boxcore.api.BoostService;
import org.bukkit.Bukkit;

import java.util.UUID;

// Only this class references com.dex.boxcore.api. Load it only if BoxCore is enabled.
public final class BoxCoreHook {
    private static BoostService service;

    /** Call once in onEnable (and after reload). */
    public static void init() {
        service = Bukkit.getPluginManager().isPluginEnabled("BoxCore")
                ? Bukkit.getServicesManager().load(BoostService.class)
                : null;
    }

    public static long multiply(UUID player, String type, long amount) {
        return service == null ? amount : service.multiply(player, type, amount);
    }

    public static int multiplier(UUID player, String type) {
        return service == null ? 1 : service.getMultiplier(player, type);
    }

    public static boolean active() {
        return service != null;
    }
}
