package com.dex.boxcore.display;

import com.dex.boxcore.boost.EffectiveCache;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * PlaceholderAPI expansion {@code boxcore} (§7.3): {@code %boxcore_multiple_<type>%}.
 * {@link #onRequest} may run on async threads, so it only reads the effective-multiplier cache.
 */
public final class BoxCoreExpansion extends PlaceholderExpansion {

    private static final String MULTIPLE = "multiple_";

    private final EffectiveCache cache;
    private final String version;

    public BoxCoreExpansion(EffectiveCache cache, String version) {
        this.cache = cache;
        this.version = version;
    }

    @Override
    public String getIdentifier() {
        return "boxcore";
    }

    @Override
    public String getAuthor() {
        return "Dex";
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null || !params.startsWith(MULTIPLE)) return null;
        try {
            if (player == null) return "1";
            return Integer.toString(cache.get(player.getUniqueId(), params.substring(MULTIPLE.length())));
        } catch (Throwable t) {
            return "1";
        }
    }
}
