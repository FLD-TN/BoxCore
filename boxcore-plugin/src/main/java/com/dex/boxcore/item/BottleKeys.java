package com.dex.boxcore.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** PDC keys of boost bottles, namespace {@code boxcore}. */
public record BottleKeys(NamespacedKey type, NamespacedKey multiplier, NamespacedKey duration) {

    public static BottleKeys of(Plugin plugin) {
        return new BottleKeys(new NamespacedKey(plugin, "boost_type"),
                new NamespacedKey(plugin, "boost_multiplier"),
                new NamespacedKey(plugin, "boost_duration"));
    }
}
