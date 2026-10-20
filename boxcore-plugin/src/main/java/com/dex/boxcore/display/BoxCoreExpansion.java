package com.dex.boxcore.display;

import com.dex.boxcore.boost.EffectiveCache;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.gem.GemFormat;
import com.dex.boxcore.gem.GemLedger;
import com.dex.boxcore.gem.GemTop;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.function.Supplier;

/**
 * PlaceholderAPI expansion {@code boxcore} (§7.3, Update 03 §6): {@code %boxcore_multiple_<type>%} and the gem
 * placeholders. {@link #onRequest} may run on async threads, so it only reads the effective-multiplier cache,
 * the in-memory gem balances and the leaderboard snapshot; it never touches the database.
 */
public final class BoxCoreExpansion extends PlaceholderExpansion {

    private static final String MULTIPLE = "multiple_";
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
                return Integer.toString(cache.get(player.getUniqueId(), params.substring(MULTIPLE.length())));
            } catch (Throwable t) {
                return "1";
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
