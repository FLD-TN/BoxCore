package com.dex.boxcore.gem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Leaderboard snapshot (Update 03 §5.3): rebuilt on the DB thread, published through a volatile immutable
 * list, read by {@code /gem top} and placeholders from any thread without touching the database.
 */
public final class GemTop {

    public record Entry(String name, long balance) {}

    /** {@code builtAt} is 0 until the first refresh completes. */
    public record Snapshot(List<Entry> entries, long builtAt) {}

    private final GemLedger ledger;
    private final LongSupplier clock;
    private final Logger logger;
    private volatile Snapshot snapshot = new Snapshot(List.of(), 0);

    public GemTop(GemLedger ledger, LongSupplier clock, Logger logger) {
        this.ledger = ledger;
        this.clock = clock;
        this.logger = logger;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /** Queues one indexed query; the new snapshot is published when it completes. */
    public void refresh() {
        GemDatabase db = ledger.database();
        if (db == null) return;
        GemConfig cfg = ledger.config();
        int size = cfg.topSize();
        db.submit(c -> GemDatabase.top(c, size + cfg.topExclude().size()))
                .whenComplete((rows, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Could not refresh the gem leaderboard", error);
                        return;
                    }
                    snapshot = new Snapshot(filter(rows, cfg, size), clock.getAsLong());
                });
    }

    static List<Entry> filter(List<GemDatabase.TopEntry> rows, GemConfig cfg, int size) {
        List<Entry> out = new ArrayList<>(Math.min(size, rows.size()));
        for (GemDatabase.TopEntry row : rows) {
            if (out.size() >= size) break;
            if (cfg.topExclude().contains(row.name().toLowerCase(Locale.ROOT))) continue;
            out.add(new Entry(row.name(), row.balance()));
        }
        return List.copyOf(out);
    }
}
