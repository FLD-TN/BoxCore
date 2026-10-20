package com.dex.boxcore.gem;

import com.dex.boxcore.api.GemResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The only owner of gem balances (Update 03 §1.3, §3). Online balances live in memory and change on the main
 * thread; every change queues one delta write to {@link GemDatabase}. Offline admin changes run entirely on the
 * DB thread (§3.4). When the database could not be opened, every operation reports "not loaded".
 */
public final class GemLedger {

    public enum PayResult { SUCCESS, INVALID_AMOUNT, SENDER_NOT_LOADED, TARGET_NOT_LOADED, INSUFFICIENT_FUNDS, TARGET_FULL }

    public enum Action { GIVE, TAKE, SET }

    /**
     * Result of an admin change. {@code balance} is the balance after the change, or the unchanged balance when
     * refused; {@code delta} is 0 when refused.
     */
    public record Change(GemResult result, long balance, long delta) {}

    /** Balance and newest transactions for {@code /boxcore gem check}; {@code balance} is null if there is no row. */
    public record Check(Long balance, List<GemDatabase.TxRow> recent) {}

    /** A committed stored change and the id of its transaction row (0 if nothing was written). */
    private record Stored(Change change, long txId) {}

    private static final long PRELOGIN_TIMEOUT_SECONDS = 5;
    private static final long PENDING_TTL_MS = 60_000L;

    /** A loaded balance. Written on the main thread (online) or inside map operations (pending); read anywhere. */
    static final class Account {
        volatile long balance;
        /** Id of the last transaction included in {@link #balance} by a load or an offline change (§3.4). */
        volatile long seenTx;
        final String storedName;
        final long loadedAt;

        Account(long balance, long seenTx, String storedName, long loadedAt) {
            this.balance = balance;
            this.seenTx = seenTx;
            this.storedName = storedName;
            this.loadedAt = loadedAt;
        }
    }

    private final Supplier<GemConfig> config;
    private final GemDatabase db;
    private final Executor main;
    private final Predicate<UUID> isOnline;
    private final LongSupplier clock;
    private final Logger logger;

    private final Map<UUID, Account> online = new ConcurrentHashMap<>();
    /** Loaded in AsyncPlayerPreLoginEvent, moved to {@link #online} on join. */
    private final Map<UUID, Account> pending = new ConcurrentHashMap<>();
    /** Set when BoxCore disables: plugins may still hold the old GemService, it must refuse everything. */
    private volatile boolean closed;

    /**
     * @param db       null when gem storage is disabled
     * @param main     runs a task on the main thread (drops it once the plugin is disabled)
     * @param isOnline whether a player is currently online (main thread)
     */
    public GemLedger(Supplier<GemConfig> config, GemDatabase db, Executor main, Predicate<UUID> isOnline,
                     LongSupplier clock, Logger logger) {
        this.config = config;
        this.db = db;
        this.main = main;
        this.isOnline = isOnline;
        this.clock = clock;
        this.logger = logger;
    }

    public boolean enabled() {
        return db != null && !closed;
    }

    /**
     * Called first in onDisable: from now on every read returns "not loaded" and every change is refused, so a
     * plugin that kept a reference to the old {@code GemService} can never change a balance that would not be
     * written any more. Queued writes are still committed by {@link GemDatabase#close()}.
     */
    public void shutdown() {
        closed = true;
        online.clear();
        pending.clear();
    }

    public GemDatabase database() {
        return db;
    }

    public GemConfig config() {
        return config.get();
    }

    // ------------------------------------------------------------------ reads (any thread)

    /** Balance of an online, loaded player; -1 if not loaded. */
    public long balance(UUID player) {
        Account account = online.get(player);
        return account == null ? -1 : account.balance;
    }

    public boolean isLoaded(UUID player) {
        return online.containsKey(player);
    }

    // ------------------------------------------------------------------ online changes (main thread)

    public GemResult deposit(UUID player, long amount, String rawReason) {
        String reason = GemFormat.sanitizeReason(rawReason);
        if (amount < 1 || reason == null) return GemResult.INVALID_AMOUNT;
        Account account = online.get(player);
        if (account == null) return GemResult.NOT_LOADED;
        if (amount > config.get().maxBalance() - account.balance) return GemResult.BALANCE_LIMIT;
        apply(player, account, amount, reason);
        return GemResult.SUCCESS;
    }

    public GemResult withdraw(UUID player, long amount, String rawReason) {
        String reason = GemFormat.sanitizeReason(rawReason);
        if (amount < 1 || reason == null) return GemResult.INVALID_AMOUNT;
        Account account = online.get(player);
        if (account == null) return GemResult.NOT_LOADED;
        if (account.balance < amount) return GemResult.INSUFFICIENT_FUNDS;
        apply(player, account, -amount, reason);
        return GemResult.SUCCESS;
    }

    /** Sets the exact balance of an online, loaded player ({@code 0..max}). Logged as the difference. */
    public Change set(UUID player, long target, String rawReason) {
        String reason = GemFormat.sanitizeReason(rawReason);
        Account account = online.get(player);
        if (account == null) return new Change(GemResult.NOT_LOADED, -1, 0);
        if (target < 0 || reason == null) return new Change(GemResult.INVALID_AMOUNT, account.balance, 0);
        if (target > config.get().maxBalance()) return new Change(GemResult.BALANCE_LIMIT, account.balance, 0);
        long delta = target - account.balance;
        if (delta != 0) apply(player, account, delta, reason);
        return new Change(GemResult.SUCCESS, target, delta);
    }

    /** Admin give/take on an online, loaded player, with the resulting balance. */
    public Change change(UUID player, Action action, long amount, String reason) {
        if (action == Action.SET) return set(player, amount, reason);
        Account account = online.get(player);
        GemResult result = action == Action.GIVE ? deposit(player, amount, reason) : withdraw(player, amount, reason);
        long balance = account == null ? -1 : account.balance;
        long delta = result != GemResult.SUCCESS ? 0 : action == Action.GIVE ? amount : -amount;
        return new Change(result, balance, delta);
    }

    /**
     * {@code /gem pay}: both sides change in one step on the main thread and are written in one SQL transaction
     * (§5.2 step 7). Permission, visibility, cooldown and amount rules are checked by the command.
     */
    public PayResult pay(UUID from, UUID to, long amount) {
        if (amount < 1 || from.equals(to)) return PayResult.INVALID_AMOUNT;
        Account sender = online.get(from);
        if (sender == null) return PayResult.SENDER_NOT_LOADED;
        Account target = online.get(to);
        if (target == null) return PayResult.TARGET_NOT_LOADED;
        if (sender.balance < amount) return PayResult.INSUFFICIENT_FUNDS;
        if (amount > config.get().maxBalance() - target.balance) return PayResult.TARGET_FULL;

        sender.balance -= amount;
        target.balance += amount;
        long senderAfter = sender.balance;
        long targetAfter = target.balance;
        long now = clock.getAsLong();
        String toReason = "pay:to:" + to;
        String fromReason = "pay:from:" + from;
        // both sides in one write: always committed (or retried) together
        db.write("pay " + from + " -> " + to + " amount=" + amount, c -> {
            GemDatabase.writeChange(c, from, -amount, senderAfter, toReason, now);
            GemDatabase.writeChange(c, to, amount, targetAfter, fromReason, now);
            return null;
        });
        return PayResult.SUCCESS;
    }

    private void apply(UUID player, Account account, long delta, String reason) {
        account.balance += delta;
        long after = account.balance;
        long now = clock.getAsLong();
        db.write("player=" + player + " delta=" + delta + " reason=" + reason, c -> {
            GemDatabase.writeChange(c, player, delta, after, reason, now);
            return null;
        });
    }

    // ------------------------------------------------------------------ admin changes by uuid (§3.4)

    /**
     * Admin change through the database, for players who are offline or online but not loaded yet. Runs in one
     * SQL transaction on the DB thread; creates the row with the starting balance if the player was never loaded.
     * The returned future completes on the main thread, after the change was mirrored into a loaded balance.
     */
    public CompletableFuture<Change> changeStored(UUID player, String name, Action action, long amount, String rawReason) {
        CompletableFuture<Change> done = new CompletableFuture<>();
        String reason = GemFormat.sanitizeReason(rawReason);
        if (!enabled() || reason == null || amount < 0 || (amount == 0 && action != Action.SET)) {
            done.complete(new Change(!enabled() ? GemResult.NOT_LOADED : GemResult.INVALID_AMOUNT, -1, 0));
            return done;
        }
        GemConfig cfg = config.get();
        long now = clock.getAsLong();
        db.submit(c -> {
            Stored stored = GemDatabase.inTransaction(c, conn -> {
                Long row = GemDatabase.balance(conn, player);
                long balance;
                if (row == null) {
                    balance = cfg.startingBalance();
                    GemDatabase.insertRow(conn, player, name, balance, now);
                    if (balance > 0) GemDatabase.insertTx(conn, player, now, balance, balance, "start");
                } else {
                    balance = row;
                }
                long delta;
                switch (action) {
                    case GIVE -> {
                        if (amount > cfg.maxBalance() - balance) return refused(GemResult.BALANCE_LIMIT, balance);
                        delta = amount;
                    }
                    case TAKE -> {
                        if (balance < amount) return refused(GemResult.INSUFFICIENT_FUNDS, balance);
                        delta = -amount;
                    }
                    default -> {
                        if (amount > cfg.maxBalance()) return refused(GemResult.BALANCE_LIMIT, balance);
                        delta = amount - balance;
                    }
                }
                long txId = 0;
                if (delta != 0) {
                    GemDatabase.addDelta(conn, player, delta, now);
                    txId = GemDatabase.insertTx(conn, player, now, delta, balance + delta, reason);
                }
                return new Stored(new Change(GemResult.SUCCESS, balance + delta, delta), txId);
            });
            if (stored.txId() > 0) mirrorIntoPending(player, stored.change().delta(), stored.txId());
            return stored;
        }).whenComplete((stored, error) -> main.execute(() -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Gem change failed: player=" + player + " action=" + action
                        + " amount=" + amount + " reason=" + reason, error);
                done.complete(new Change(GemResult.NOT_LOADED, -1, 0));
                return;
            }
            if (stored.txId() > 0) mirrorIntoOnline(player, stored.change().delta(), stored.txId());
            done.complete(stored.change());
        }));
        return done;
    }

    private static Stored refused(GemResult result, long balance) {
        return new Stored(new Change(result, balance, 0), 0);
    }

    /** DB thread, right after the commit: a pending (pre-login) balance that did not see this change gets it. */
    void mirrorIntoPending(UUID player, long delta, long txId) {
        pending.computeIfPresent(player, (id, account) -> {
            if (txId > account.seenTx) {
                account.balance += delta;
                account.seenTx = txId;
            }
            return account;
        });
    }

    /** Main thread: an online balance loaded before this change gets it, exactly once. */
    void mirrorIntoOnline(UUID player, long delta, long txId) {
        Account account = online.get(player);
        if (account != null && txId > account.seenTx) {
            account.balance += delta;
            account.seenTx = txId;
        }
    }

    /** Balance (cache if loaded, otherwise the database) and newest transactions; completes on the main thread. */
    public CompletableFuture<Check> check(UUID player, int limit) {
        CompletableFuture<Check> done = new CompletableFuture<>();
        if (!enabled()) {
            done.complete(new Check(null, List.of()));
            return done;
        }
        db.submit(c -> new Check(GemDatabase.balance(c, player), GemDatabase.recent(c, player, limit)))
                .whenComplete((value, error) -> main.execute(() -> {
                    if (error != null) {
                        done.completeExceptionally(error);
                        return;
                    }
                    Account account = online.get(player);
                    done.complete(account == null ? value : new Check(account.balance, value.recent()));
                }));
        return done;
    }

    // ------------------------------------------------------------------ sessions (§3.2)

    /** AsyncPlayerPreLoginEvent thread: loads the balance (waits up to 5 s) into the pending map. */
    public void preLogin(UUID player, String name) {
        if (!enabled() || online.containsKey(player)) return; // duplicate login: join reloads after the old quit
        long starting = config.get().startingBalance();
        long now = clock.getAsLong();
        try {
            db.submit(c -> {
                GemDatabase.LoadResult r = GemDatabase.load(c, player, name, starting, now);
                pending.put(player, new Account(r.balance(), r.seenTx(), r.name(), clock.getAsLong()));
                return r;
            }).get(PRELOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Could not load the gems of " + name + " before login, retrying after join", ex);
        }
    }

    /** Main thread, PlayerJoinEvent. */
    public void join(UUID player, String name) {
        if (!enabled()) return;
        Account account = pending.remove(player);
        if (account != null) {
            online.put(player, account);
            renameIfChanged(player, account, name);
            return;
        }
        if (!online.containsKey(player)) load(player, name);
    }

    /** Main thread, PlayerQuitEvent. Nothing to flush: every change is already queued. */
    public void quit(UUID player) {
        online.remove(player);
    }

    /** Main thread: loads online players (plugin enable with players online). */
    public void loadOnline(Map<UUID, String> players) {
        if (!enabled()) return;
        for (Map.Entry<UUID, String> e : players.entrySet()) {
            if (!online.containsKey(e.getKey())) load(e.getKey(), e.getValue());
        }
    }

    /** Drops pre-login loads whose player never joined. */
    public void purgePending() {
        long now = clock.getAsLong();
        pending.values().removeIf(account -> now - account.loadedAt > PENDING_TTL_MS);
    }

    private void load(UUID player, String name) {
        long starting = config.get().startingBalance();
        long now = clock.getAsLong();
        db.submit(c -> GemDatabase.load(c, player, name, starting, now))
                .whenComplete((r, error) -> main.execute(() -> {
                    if (error != null) {
                        logger.log(Level.SEVERE, "Could not load the gems of " + name + " (" + player + ")", error);
                        return;
                    }
                    if (!isOnline.test(player) || online.containsKey(player)) return;
                    Account account = new Account(r.balance(), r.seenTx(), r.name(), now);
                    online.put(player, account);
                    renameIfChanged(player, account, name);
                }));
    }

    private void renameIfChanged(UUID player, Account account, String name) {
        if (name == null || name.equals(account.storedName)) return;
        db.write("rename " + player + " to " + name, c -> {
            GemDatabase.updateName(c, player, name);
            return null;
        });
    }

    /** For tests: whether a pre-login load is waiting for the join. */
    boolean isPending(UUID player) {
        return pending.containsKey(player);
    }
}
