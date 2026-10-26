package com.dex.boxcore.item;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * PDC keys of boost bottles, namespace {@code boxcore}. A whole bottle carries {@code boost_multiplier} (whole
 * number, unchanged since 1.0.0 so old and new bottles stack); a decimal bottle carries
 * {@code boost_multiplier_tenths} instead (Update 04 D5). A clan bottle also carries {@code boost_scope = "clan"}
 * (Update 05 E3.4); a bottle without it is personal.
 */
public record BottleKeys(NamespacedKey type, NamespacedKey multiplier, NamespacedKey multiplierTenths,
                         NamespacedKey duration, NamespacedKey scope) {

    /** Value of {@link #scope()} on a clan bottle. */
    public static final String SCOPE_CLAN = "clan";

    public static BottleKeys of(Plugin plugin) {
        return of(plugin.getName().toLowerCase(java.util.Locale.ROOT));
    }

    /** The keys in a namespace ({@code boxcore} for the plugin). */
    public static BottleKeys of(String namespace) {
        return new BottleKeys(new NamespacedKey(namespace, "boost_type"),
                new NamespacedKey(namespace, "boost_multiplier"),
                new NamespacedKey(namespace, "boost_multiplier_tenths"),
                new NamespacedKey(namespace, "boost_duration"),
                new NamespacedKey(namespace, "boost_scope"));
    }
}
