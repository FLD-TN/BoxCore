package com.dex.boxcore.display;

import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.boost.EffectiveCache;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.gem.GemFormat;
import com.dex.boxcore.gem.GemLedger;
import com.dex.boxcore.gem.GemTop;
import com.dex.boxcore.util.DurationFormat;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.function.Supplier;

/**
 * PlaceholderAPI expansion {@code boxcore} (§7.3, Update 03 §6, Update 04 D7): {@code %boxcore_multiple_<type>%},
 * {@code %boxcore_multiple_<type>_raw%}, the clan boost ({@code %boxcore_clan_<type>%}, {@code _remaining}, Update 05)
 * and the gem
 * placeholders. {@link #onRequest} may run on async threads, so it only reads the effective-multiplier cache,
 * the in-memory gem balances and the leaderboard snapshot; it never touches the database.
 */
public final class BoxCoreExpansion extends PlaceholderExpansion {

    private static final String MULTIPLE = "multiple_";
    private static final String RAW = "_raw";
    private static final String CLAN = "clan_";
    private static final String REMAINING = "_remaining";
    private static final String GEM = "gem";
    private static final String GEM_TOP = "gem_top_";

    private final EffectiveCache cache;
    private final GemLedger gems;
    private final GemTop top;
    private final Supplier<Messages> messages;
    private final String version;

    public BoxCoreExpansion(EffectiveCache cache, GemLedger gems, GemTop top, Supplier<Messages> messages,
                            String version) {
        this.cache = cache;
        this.gems = gems;
        this.top = top;
        this.messages = messages;
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
        if (params == null) return null;
        if (params.startsWith(MULTIPLE)) {
            try {
                if (player == null) return "1";
                // Update 04 D7: <type>_raw always uses '.', <type> the configured separator
                String type = params.substring(MULTIPLE.length());
                boolean raw = type.endsWith(RAW);
                if (raw) type = type.substring(0, type.length() - RAW.length());
                int tenths = cache.get(player.getUniqueId(), type);
                return raw ? Multipliers.format(tenths) : messages.get().multiplier(tenths);
            } catch (Throwable t) {
                return "1";
            }
        }
        if (params.startsWith(CLAN)) {
            try {
                return clan(player, params.substring(CLAN.length()));
            } catch (Throwable t) {
                return "";
            }
        }
        if (params.startsWith(GEM)) {
            try {
                return gem(player, params);
            } catch (Throwable t) {
                return "";
            }
        }
        return null;
    }

    /**
     * Update 05 E3.5: {@code clan_<type>} = multiplier of the player's clan boost ({@code 1} if none),
     * {@code clan_<type>_remaining} = its remaining time ({@code ""} if none). Read from the cache snapshot, safe
     * from any thread.
     */
    private String clan(OfflinePlayer player, String rest) {
        boolean remaining = rest.endsWith(REMAINING);
        String type = remaining ? rest.substring(0, rest.length() - REMAINING.length()) : rest;
        EffectiveCache.Snapshot s = player == null ? null : cache.snapshot(player.getUniqueId());
        long now = System.currentTimeMillis();
        int tenths = s == null ? 0 : s.clan(type);
        long expiresAt = s == null ? 0 : s.clanExpiresAt(type);
        if (tenths == 0 || expiresAt <= now) return remaining ? "" : "1";
        return remaining ? DurationFormat.clock(DurationFormat.remainingSeconds(expiresAt, now))
                : messages.get().multiplier(tenths);
    }

    private String gem(OfflinePlayer player, String params) {
        if (params.startsWith(GEM_TOP)) return top(params.substring(GEM_TOP.length()));
        return switch (params) {
            case "gem" -> balance(player, false);
            case "gem_formatted" -> balance(player, true);
            case "gem_short" -> {
                long balance = player == null ? -1 : gems.balance(player.getUniqueId());
                yield balance < 0 ? gems.config().placeholderUnknown() : GemFormat.shortForm(balance);
            }
            case "gem_name" -> gems.config().displayName();
            default -> null;
        };
    }

    private String balance(OfflinePlayer player, boolean grouped) {
        long balance = player == null ? -1 : gems.balance(player.getUniqueId());
        if (balance < 0) return gems.config().placeholderUnknown();
        return grouped ? GemFormat.grouped(balance) : Long.toString(balance);
    }

    /** {@code <n>_name} or {@code <n>_amount}, n 1-based. */
    private String top(String rest) {
        int underscore = rest.indexOf('_');
        if (underscore <= 0) return null;
        long rank = GemFormat.parseAmount(rest.substring(0, underscore));
        String field = rest.substring(underscore + 1);
        boolean name = field.equals("name");
        if (!name && !field.equals("amount")) return null;
        List<GemTop.Entry> entries = top.snapshot().entries();
        if (rank < 1 || rank > entries.size()) {
            return messages.get().raw(name ? "gem.top.empty-name" : "gem.top.empty-amount");
        }
        GemTop.Entry entry = entries.get((int) rank - 1);
        return name ? entry.name() : GemFormat.grouped(entry.balance());
    }
}
