package com.dex.boxcore.boost;

import com.dex.boxcore.api.BonusProvider;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.util.ErrorReporter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/**
 * Registered {@link BonusProvider}s (BoxCore 1.6.0), at most one per plugin. Every call is guarded: a provider
 * that throws counts as 0, so a broken plugin can never break boosts. Main thread only.
 */
public final class BonusLink {

    private final ErrorReporter errors;
    private final Map<Plugin, BonusProvider> providers = new LinkedHashMap<>();

    public BonusLink(ErrorReporter errors) {
        this.errors = errors;
    }

    public void set(Plugin owner, BonusProvider provider) {
        providers.put(owner, provider);
    }

    /** Removes the provider of {@code plugin}. @return true if one was removed */
    public boolean removeIfOwner(Plugin plugin) {
        return providers.remove(plugin) != null;
    }

    public boolean available() {
        return !providers.isEmpty();
    }

    /** Sum of every provider's bonus for the player and type, in tenths, never negative. */
    public int bonus(UUID player, String type) {
        if (providers.isEmpty() || player == null || type == null) return 0;
        long sum = 0;
        for (Map.Entry<Plugin, BonusProvider> e : providers.entrySet()) {
            try {
                sum += Math.max(0, e.getValue().bonusTenths(player, type));
            } catch (Throwable t) {
                errors.report("bonus provider " + e.getKey().getName(), t);
            }
        }
        return (int) Math.min(sum, BoostType.LIMIT_TENTHS);
    }
}
