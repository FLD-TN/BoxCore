package com.dex.boxcore.display;

import com.dex.boxcore.boost.BoostRegistry;
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

/**
 * Bossbars (§7.2): one shared bar per active global type, and per player at most one bar per type for the
 * highest active personal boost. Keeps no {@link Player} references; bars are keyed by type and UUID.
 * Main thread only.
 */
public final class BossBarService {

    private static final class GlobalBar {
        final BossBar bar;
        GlobalBoost boost;
        String title;

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
        String title;

        PersonalBar(BossBar bar) {
            this.bar = bar;
        }

        /** @return true if anything shown in the title changed */
        boolean set(PersonalBoost boost, int effective, int rank, int global) {
            if (boost.equals(this.boost) && effective == this.effective && rank == this.rank && global == this.global) {
                return false;
            }
            this.boost = boost;
            this.effective = effective;
            this.rank = rank;
            this.global = global;
            this.title = null;
            return true;
        }
    }

    private final Supplier<CoreConfig> config;
    private final Supplier<Messages> messages;
    private final Map<String, GlobalBar> globalBars = new HashMap<>();
    private final Map<UUID, Map<String, PersonalBar>> personalBars = new HashMap<>();

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
                existing.title = null;
                updateGlobal(existing, type, now);
            }
        }
    }

    private void updateGlobal(GlobalBar gb, BoostType type, long now) {
        String title = Messages.fill(messages.get().raw("bossbar.global"),
                "type", type.id(),
                "type_name", type.displayName(),
                "multiplier", gb.boost.multiplier(),
                "remaining", DurationFormat.clock(DurationFormat.remainingSeconds(gb.boost.expiresAt(), now)));
        if (title.equals(gb.title)) return;
        gb.title = title;
        gb.bar.name(messages.get().parse(type, title));
        gb.bar.progress(gb.boost.progress(now));
    }

    // ------------------------------------------------------------------ personal

    /** Rebuilds a player's personal bars from the registry and their effective multipliers. */
    public void updatePlayer(Player player, EffectiveCache.Snapshot snapshot, BoostRegistry registry, long now) {
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
            int effective = snapshot == null ? shown.multiplier() : snapshot.effective(type.id());
            int rank = snapshot == null ? 0 : snapshot.rank(type.id());
            int global = snapshot == null ? 0 : snapshot.global(type.id());
            if (bars == null) {
                bars = new HashMap<>(4);
                personalBars.put(id, bars);
            }
            PersonalBar pb = bars.get(type.id());
            if (pb == null) {
                pb = new PersonalBar(newBar(type));
                pb.set(shown, effective, rank, global);
                updatePersonal(pb, type, now);
                bars.put(type.id(), pb);
                player.showBossBar(pb.bar);
            } else if (pb.set(shown, effective, rank, global)) {
                updatePersonal(pb, type, now);
            }
        }
        if (bars != null && bars.isEmpty()) personalBars.remove(id);
    }

    private void updatePersonal(PersonalBar pb, BoostType type, long now) {
        Messages msg = messages.get();
        String remaining = DurationFormat.clock(DurationFormat.remainingSeconds(pb.boost.expiresAt(), now));
        String title = Messages.fill(msg.raw("bossbar.personal"),
                "type", type.id(),
                "type_name", type.displayName(),
                "multiplier", pb.boost.multiplier(),
                "remaining", remaining,
                "effective", pb.effective);
        // U3: "đang dùng" when the global boost determines the value, "tổng" when stacked with a rank
        if (MultiplierCalculator.globalWins(pb.rank, pb.boost.multiplier(), pb.global)) {
            title = title + Messages.fill(msg.raw("bossbar.personal-suffix"), "effective", pb.effective);
        } else if (pb.rank > 0) {
            title = title + Messages.fill(msg.raw("bossbar.personal-suffix-total"), "effective", pb.effective);
        }
        if (title.equals(pb.title)) return;
        pb.title = title;
        pb.bar.name(msg.parse(type, title));
        pb.bar.progress(pb.boost.progress(now));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Updates titles and progress of all visible bars; setters are only called when the text changed. */
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
    }

    private static BossBar newBar(BoostType type) {
        return BossBar.bossBar(Component.empty(), 1f, type.color(), BossBar.Overlay.PROGRESS);
    }

    private static void hideFromAll(BossBar bar) {
        for (Player player : Bukkit.getOnlinePlayers()) player.hideBossBar(bar);
    }
}
