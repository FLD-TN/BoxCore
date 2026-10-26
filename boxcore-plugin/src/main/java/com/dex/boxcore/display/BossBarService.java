package com.dex.boxcore.display;

import com.dex.boxcore.boost.BoostRegistry;
import com.dex.boxcore.boost.ClanBoost;
import com.dex.boxcore.boost.EffectiveCache;
import com.dex.boxcore.boost.GlobalBoost;
import com.dex.boxcore.boost.MultiplierCalculator;
import com.dex.boxcore.boost.PersonalBoost;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.util.DurationFormat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Bossbars (§7.2): one shared bar per active global type, and per player at most one bar per type for the
 * highest active personal boost, plus one per type for the boost of the player's clan (Update 05 E3.5; per player,
 * because the "đang dùng" suffix depends on the player). Keeps no {@link Player} references; bars are keyed by type
 * and UUID. Main thread only.
 *
 * <p>A title is parsed (placeholders, small caps, MiniMessage) only when what it shows changes; the remaining time,
 * which changes every second, is a marker in the parsed title that {@link #withRemaining} replaces with plain text
 * (keeping the style around it), so the per-second update does no parsing.
 */
public final class BossBarService {

    /** Stands for {@code %remaining%} in a parsed title: a private-use character that small caps and MiniMessage keep. */
    static final String REMAINING = "\uE000";
    private static final Pattern REMAINING_PATTERN = Pattern.compile(REMAINING, Pattern.LITERAL);

    private static final class GlobalBar {
        final BossBar bar;
        GlobalBoost boost;
        /** Parsed title with the {@link #REMAINING} marker, {@code null} when it must be rebuilt. */
        Component base;
        /** Remaining time last shown. */
        String remaining;

        GlobalBar(BossBar bar, GlobalBoost boost) {
            this.bar = bar;
            this.boost = boost;
        }
    }

    private static final class PersonalBar {
        final BossBar bar;
        PersonalBoost boost;
        int effective;
        int rank;
        int global;
        int clan;
        /** Parsed title with the {@link #REMAINING} marker, {@code null} when it must be rebuilt. */
        Component base;
        /** Remaining time last shown. */
        String remaining;

        PersonalBar(BossBar bar) {
            this.bar = bar;
        }

        /** @return true if anything shown in the title changed */
        boolean set(PersonalBoost boost, int effective, int rank, int global, int clan) {
            if (boost.equals(this.boost) && effective == this.effective && rank == this.rank && global == this.global
                    && clan == this.clan) {
                return false;
            }
            this.boost = boost;
            this.effective = effective;
            this.rank = rank;
            this.global = global;
            this.clan = clan;
            this.base = null;
            return true;
        }
    }

    private static final class ClanBar {
        final BossBar bar;
        ClanBoost boost;
        int effective;
        /** Parsed title with the {@link #REMAINING} marker, {@code null} when it must be rebuilt. */
        Component base;
        /** Remaining time last shown. */
        String remaining;

        ClanBar(BossBar bar) {
            this.bar = bar;
        }

        /** @return true if anything shown in the title changed */
        boolean set(ClanBoost boost, int effective) {
            if (boost.equals(this.boost) && effective == this.effective) return false;
            this.boost = boost;
            this.effective = effective;
            this.base = null;
            return true;
        }
    }

    private final Supplier<CoreConfig> config;
    private final Supplier<Messages> messages;
    private final Map<String, GlobalBar> globalBars = new HashMap<>();
    private final Map<UUID, Map<String, PersonalBar>> personalBars = new HashMap<>();
    private final Map<UUID, Map<String, ClanBar>> clanBars = new HashMap<>();

    public BossBarService(Supplier<CoreConfig> config, Supplier<Messages> messages) {
        this.config = config;
        this.messages = messages;
    }

    // ------------------------------------------------------------------ global

    /** Creates, updates or removes global bars to match the registry; shows new bars to every online player. */
    public void syncGlobals(BoostRegistry registry, long now) {
        CoreConfig cfg = config.get();
        for (Iterator<Map.Entry<String, GlobalBar>> it = globalBars.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, GlobalBar> e = it.next();
            GlobalBoost active = registry.global(e.getKey());
            if (active == null || active.isExpired(now) || cfg.type(e.getKey()) == null) {
                hideFromAll(e.getValue().bar);
                it.remove();
            }
        }
        for (BoostType type : cfg.typeList()) {
            GlobalBoost boost = registry.global(type.id());
            if (boost == null || boost.isExpired(now)) continue;
            GlobalBar existing = globalBars.get(type.id());
            if (existing == null) {
                GlobalBar created = new GlobalBar(newBar(type), boost);
                updateGlobal(created, type, now);
                globalBars.put(type.id(), created);
                for (Player player : Bukkit.getOnlinePlayers()) player.showBossBar(created.bar);
            } else if (!existing.boost.equals(boost)) {
                existing.boost = boost;
                existing.base = null;
                updateGlobal(existing, type, now);
            }
        }
    }

    private void updateGlobal(GlobalBar gb, BoostType type, long now) {
        if (gb.base == null) {
            Messages msg = messages.get();
            gb.base = msg.parseStyled(type, Messages.fill(msg.raw("bossbar.global"),
                    "type", type.id(),
                    "type_name", type.displayName(),
                    "multiplier", msg.multiplier(gb.boost.tenths()),
                    "remaining", REMAINING));
            gb.remaining = null;
        }
        String remaining = DurationFormat.clock(DurationFormat.remainingSeconds(gb.boost.expiresAt(), now));
        if (remaining.equals(gb.remaining)) return;
        gb.remaining = remaining;
        gb.bar.name(withRemaining(gb.base, remaining));
        gb.bar.progress(gb.boost.progress(now));
    }

    // ------------------------------------------------------------------ personal

    /** Rebuilds a player's personal and clan bars from the registry and their effective multipliers. */
    public void updatePlayer(Player player, EffectiveCache.Snapshot snapshot, BoostRegistry registry, long now) {
        updatePersonalBars(player, snapshot, registry, now);
        updateClanBars(player, snapshot, registry, now);
    }

    private void updatePersonalBars(Player player, EffectiveCache.Snapshot snapshot, BoostRegistry registry, long now) {
        CoreConfig cfg = config.get();
        UUID id = player.getUniqueId();
        List<PersonalBoost> personal = registry.personal(id);
        Map<String, PersonalBar> bars = personalBars.get(id);

        if (bars != null) {
            for (Iterator<Map.Entry<String, PersonalBar>> it = bars.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, PersonalBar> e = it.next();
                if (cfg.type(e.getKey()) == null || MultiplierCalculator.displayed(personal, e.getKey(), now) == null) {
                    player.hideBossBar(e.getValue().bar);
                    it.remove();
                }
            }
        }
        if (personal.isEmpty()) {
            if (bars != null && bars.isEmpty()) personalBars.remove(id);
            return;
        }

        for (BoostType type : cfg.typeList()) {
            PersonalBoost shown = MultiplierCalculator.displayed(personal, type.id(), now);
            if (shown == null) continue;
            int effective = snapshot == null ? shown.tenths() : snapshot.effective(type.id());
            int rank = snapshot == null ? 0 : snapshot.rank(type.id());
            int global = snapshot == null ? 0 : snapshot.global(type.id());
            int clan = snapshot == null ? 0 : snapshot.clan(type.id());
            if (bars == null) {
                bars = new HashMap<>(4);
                personalBars.put(id, bars);
            }
            PersonalBar pb = bars.get(type.id());
            if (pb == null) {
                pb = new PersonalBar(newBar(type));
                pb.set(shown, effective, rank, global, clan);
                updatePersonal(pb, type, now);
                bars.put(type.id(), pb);
                player.showBossBar(pb.bar);
            } else if (pb.set(shown, effective, rank, global, clan)) {
                updatePersonal(pb, type, now);
            }
        }
        if (bars != null && bars.isEmpty()) personalBars.remove(id);
    }

    private void updatePersonal(PersonalBar pb, BoostType type, long now) {
        if (pb.base == null) {
            pb.base = personalTitle(messages.get(), type, pb.boost.tenths(), pb.effective, pb.rank, pb.global, pb.clan);
            pb.remaining = null;
        }
        String remaining = DurationFormat.clock(DurationFormat.remainingSeconds(pb.boost.expiresAt(), now));
        if (remaining.equals(pb.remaining)) return;
        pb.remaining = remaining;
        pb.bar.name(withRemaining(pb.base, remaining));
        pb.bar.progress(pb.boost.progress(now));
    }

    /** The parsed personal title with the {@link #REMAINING} marker (rebuilt only when the shown state changes). */
    static Component personalTitle(Messages msg, BoostType type, int tenths, int effective, int rank, int global) {
        return personalTitle(msg, type, tenths, effective, rank, global, 0);
    }

    /** {@link #personalTitle(Messages, BoostType, int, int, int, int)} with the clan boost (Update 05). */
    static Component personalTitle(Messages msg, BoostType type, int tenths, int effective, int rank, int global,
                                   int clan) {
        String title = Messages.fill(msg.raw("bossbar.personal"),
                "type", type.id(),
                "type_name", type.displayName(),
                "multiplier", msg.multiplier(tenths),
                "remaining", REMAINING,
                "effective", msg.multiplier(effective));
        // U3: "đang dùng" when the global (or, Update 05, the clan) boost determines the value, "tổng" when stacked
        // with a rank
        if (MultiplierCalculator.externalWins(rank, tenths, global, clan)) {
            title = title + Messages.fill(msg.raw("bossbar.personal-suffix"), "effective", msg.multiplier(effective));
        } else if (rank > 0) {
            title = title + Messages.fill(msg.raw("bossbar.personal-suffix-total"), "effective",
                    msg.multiplier(effective));
        }
        return msg.parseStyled(type, title);
    }

    // ------------------------------------------------------------------ clan (Update 05 E3.5)

    private void updateClanBars(Player player, EffectiveCache.Snapshot snapshot, BoostRegistry registry, long now) {
        CoreConfig cfg = config.get();
        UUID id = player.getUniqueId();
        UUID clan = snapshot == null ? null : snapshot.clan();
        Map<String, ClanBar> bars = clanBars.get(id);
        if (bars != null) {
            for (Iterator<Map.Entry<String, ClanBar>> it = bars.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, ClanBar> e = it.next();
                ClanBoost running = registry.clan(clan, e.getKey());
                if (cfg.type(e.getKey()) == null || running == null || running.isExpired(now)) {
                    player.hideBossBar(e.getValue().bar);
                    it.remove();
                }
            }
        }
        if (clan != null) {
            for (BoostType type : cfg.typeList()) {
                ClanBoost running = registry.clan(clan, type.id());
                if (running == null || running.isExpired(now)) continue;
                int effective = snapshot.effective(type.id());
                if (bars == null) {
                    bars = new HashMap<>(4);
                    clanBars.put(id, bars);
                }
                ClanBar cb = bars.get(type.id());
                if (cb == null) {
                    cb = new ClanBar(newBar(type));
                    cb.set(running, effective);
                    updateClan(cb, type, now);
                    bars.put(type.id(), cb);
                    player.showBossBar(cb.bar);
                } else if (cb.set(running, effective)) {
                    updateClan(cb, type, now);
                }
            }
        }
        if (bars != null && bars.isEmpty()) clanBars.remove(id);
    }

    private void updateClan(ClanBar cb, BoostType type, long now) {
        if (cb.base == null) {
            cb.base = clanTitle(messages.get(), type, cb.boost.tenths(), cb.effective);
            cb.remaining = null;
        }
        String remaining = DurationFormat.clock(DurationFormat.remainingSeconds(cb.boost.expiresAt(), now));
        if (remaining.equals(cb.remaining)) return;
        cb.remaining = remaining;
        cb.bar.name(withRemaining(cb.base, remaining));
        cb.bar.progress(cb.boost.progress(now));
    }

    /** The parsed clan title with the {@link #REMAINING} marker; "đang dùng" when the player's value is higher. */
    static Component clanTitle(Messages msg, BoostType type, int tenths, int effective) {
        String title = Messages.fill(msg.raw("bossbar.clan"),
                "type", type.id(),
                "type_name", type.displayName(),
                "multiplier", msg.multiplier(tenths),
                "remaining", REMAINING,
                "effective", msg.multiplier(effective));
        if (effective > tenths) {
            title = title + Messages.fill(msg.raw("bossbar.clan-suffix"), "effective", msg.multiplier(effective));
        }
        return msg.parseStyled(type, title);
    }

    /** {@code base} with the {@link #REMAINING} marker replaced by {@code remaining}, in the marker's style. */
    static Component withRemaining(Component base, String remaining) {
        return base.replaceText(b -> b.match(REMAINING_PATTERN).replacement(remaining));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Updates the remaining time and progress of all visible bars; setters are only called when it changed. */
    public void tick(long now) {
        CoreConfig cfg = config.get();
        for (Map.Entry<String, GlobalBar> e : globalBars.entrySet()) {
            BoostType type = cfg.type(e.getKey());
            if (type != null) updateGlobal(e.getValue(), type, now);
        }
        for (Map<String, PersonalBar> bars : personalBars.values()) {
            for (Map.Entry<String, PersonalBar> e : bars.entrySet()) {
                BoostType type = cfg.type(e.getKey());
                if (type != null) updatePersonal(e.getValue(), type, now);
            }
        }
        for (Map<String, ClanBar> bars : clanBars.values()) {
            for (Map.Entry<String, ClanBar> e : bars.entrySet()) {
                BoostType type = cfg.type(e.getKey());
                if (type != null) updateClan(e.getValue(), type, now);
            }
        }
    }

    /** Shows the active global bars to a joining player. */
    public void join(Player player) {
        for (GlobalBar gb : globalBars.values()) player.showBossBar(gb.bar);
    }

    /** Hides and discards every bar of a leaving player. */
    public void quit(Player player) {
        for (GlobalBar gb : globalBars.values()) player.hideBossBar(gb.bar);
        Map<String, PersonalBar> bars = personalBars.remove(player.getUniqueId());
        if (bars != null) {
            for (PersonalBar pb : bars.values()) player.hideBossBar(pb.bar);
        }
        Map<String, ClanBar> clan = clanBars.remove(player.getUniqueId());
        if (clan != null) {
            for (ClanBar cb : clan.values()) player.hideBossBar(cb.bar);
        }
    }

    /** Hides and discards all bars (reload, disable). */
    public void clear() {
        for (GlobalBar gb : globalBars.values()) hideFromAll(gb.bar);
        globalBars.clear();
        for (Map.Entry<UUID, Map<String, PersonalBar>> e : personalBars.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) continue;
            for (PersonalBar pb : e.getValue().values()) player.hideBossBar(pb.bar);
        }
        personalBars.clear();
        for (Map.Entry<UUID, Map<String, ClanBar>> e : clanBars.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) continue;
            for (ClanBar cb : e.getValue().values()) player.hideBossBar(cb.bar);
        }
        clanBars.clear();
    }

    private static BossBar newBar(BoostType type) {
        return BossBar.bossBar(Component.empty(), 1f, type.color(), BossBar.Overlay.PROGRESS);
    }

    private static void hideFromAll(BossBar bar) {
        for (Player player : Bukkit.getOnlinePlayers()) player.hideBossBar(bar);
    }
}
