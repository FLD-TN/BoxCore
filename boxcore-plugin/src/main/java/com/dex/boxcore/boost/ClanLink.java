package com.dex.boxcore.boost;

import com.dex.boxcore.api.ClanProvider;
import com.dex.boxcore.util.ErrorReporter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The registered {@link ClanProvider} (Update 05 E3.2), or none. Every call is guarded: a provider that throws counts
 * as "no clan", so a broken clan plugin can never break boosts. Main thread only.
 */
public final class ClanLink {

    private final ErrorReporter errors;
    private Plugin owner;
    private ClanProvider provider;

    public ClanLink(ErrorReporter errors) {
        this.errors = errors;
    }

    public void set(Plugin owner, ClanProvider provider) {
        this.owner = owner;
        this.provider = provider;
    }

    /** Removes the provider if it belongs to {@code plugin}. @return true if one was removed */
    public boolean removeIfOwner(Plugin plugin) {
        if (owner == null || !owner.equals(plugin)) return false;
        owner = null;
        provider = null;
        return true;
    }

    public boolean available() {
        return provider != null;
    }

    /** The player's clan, {@code null} if none or no provider. */
    public UUID clanOf(UUID player) {
        ClanProvider p = provider;
        if (p == null || player == null) return null;
        try {
            return p.clanOf(player);
        } catch (Throwable t) {
            errors.report("clan provider clanOf", t);
            return null;
        }
    }

    public boolean canUse(UUID player) {
        ClanProvider p = provider;
        if (p == null || player == null) return false;
        try {
            return p.canUseClanBoost(player);
        } catch (Throwable t) {
            errors.report("clan provider canUseClanBoost", t);
            return false;
        }
    }

    /** The clan's coloured name as MiniMessage, ready to insert into a message template; {@code ""} if unknown. */
    public String clanName(UUID clan) {
        ClanProvider p = provider;
        if (p == null || clan == null) return "";
        try {
            String name = p.clanName(clan);
            if (name == null || name.isEmpty()) return "";
            Component component = LegacyComponentSerializer.legacySection().deserialize(name);
            return MiniMessage.miniMessage().serialize(component);
        } catch (Throwable t) {
            errors.report("clan provider clanName", t);
            return "";
        }
    }

    public Collection<UUID> onlineMembers(UUID clan) {
        ClanProvider p = provider;
        if (p == null || clan == null) return List.of();
        try {
            Collection<UUID> members = p.onlineMembers(clan);
            return members == null ? List.of() : List.copyOf(members);
        } catch (Throwable t) {
            errors.report("clan provider onlineMembers", t);
            return List.of();
        }
    }
}
