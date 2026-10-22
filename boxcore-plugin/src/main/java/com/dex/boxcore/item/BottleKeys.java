package com.dex.boxcore.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * PDC keys of boost bottles, namespace {@code boxcore}. A whole bottle carries {@code boost_multiplier} (whole
 * number, unchanged since 1.0.0 so old and new bottles stack); a decimal bottle carries
 * {@code boost_multiplier_tenths} instead (Update 04 D5).
 */
public record BottleKeys(NamespacedKey type, NamespacedKey multiplier, NamespacedKey multiplierTenths,
                         NamespacedKey duration) {

    public static BottleKeys of(Plugin plugin) {
        return new BottleKeys(new NamespacedKey(plugin, "boost_type"),
                new NamespacedKey(plugin, "boost_multiplier"),
                new NamespacedKey(plugin, "boost_multiplier_tenths"),
                new NamespacedKey(plugin, "boost_duration"));
    }
}
