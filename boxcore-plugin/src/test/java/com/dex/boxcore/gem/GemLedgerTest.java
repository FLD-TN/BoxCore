package com.dex.boxcore.gem;

import com.dex.boxcore.api.GemResult;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ledger and database against a real SQLite file; the "main thread" is a queue drained by the test. */
class GemLedgerTest {

    private static final Logger LOG = Logger.getAnonymousLogger();
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @TempDir
    File dir;

    private final Queue<Runnable> mainQueue = new ArrayDeque<>();
    private final Set<UUID> online = new HashSet<>();
    private final AtomicLong clock = new AtomicLong(1_790_000_000_000L);
    private GemConfig config;
    private GemDatabase db;
    private GemLedger ledger;

    @BeforeEach
    void setUp() {
        config = config(0, 1_000_000);
        open();
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    private static GemConfig config(long starting, long max) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("gem.starting-balance", starting);
        yaml.set("gem.max-balance", max);
        return GemConfig.parse(yaml, LOG);
    }

    private void open() {
        db = GemDatabase.open(new File(dir, GemDatabase.FILE), LOG);
        assertNotNull(db);
        ledger = new GemLedger(() -> config, db, mainQueue::add, online::contains, clock::get, LOG);
    }

    private void reopen() {
        db.close();
        open();
    }

    /** Waits until every queued DB task ran, then runs the main-thread tasks they scheduled. */
    private void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            db.submit(c -> null).get(5, TimeUnit.SECONDS);
            Runnable task;
            while ((task = mainQueue.poll()) != null) task.run();
        }
    }

    private void login(UUID id, String name) throws Exception {
        online.add(id);
        ledger.preLogin(id, name);
        ledger.join(id, name);
        settle();
    }

    private long stored(UUID id) throws Exception {
        Long value = db.submit(c -> GemDatabase.balance(c, id)).get(5, TimeUnit.SECONDS);
        return value == null ? -1 : value;
    }

    private List<GemDatabase.TxRow> log(UUID id) throws Exception {
        return db.submit(c -> GemDatabase.recent(c, id, 100)).get(5, TimeUnit.SECONDS);
    }

    private static <T> T done(CompletableFuture<T> future) {
        assertTrue(future.isDone(), "future should be complete after settle()");
        return future.join();
    }

    // ------------------------------------------------------------------ loading

    @Test
    void newPlayerGetsStartingBalanceAndOneRow() throws Exception {
        config = config(25, 1_000_000);
        login(P1, "Steve");
        assertEquals(25, ledger.balance(P1));
        assertEquals(25, stored(P1));
        List<GemDatabase.TxRow> rows = log(P1);
        assertEquals(1, rows.size());
        assertEquals("start", rows.get(0).reason());
    }

    @Test
    void noStartRowWhenStartingBalanceIsZero() throws Exception {
        login(P1, "Steve");
        assertEquals(0, ledger.balance(P1));
        assertTrue(log(P1).isEmpty());
    }

    @Test
    void notLoadedBeforeJoinAndAfterQuit() throws Exception {
        assertEquals(-1, ledger.balance(P1));
        assertEquals(GemResult.NOT_LOADED, ledger.deposit(P1, 5, "test"));
        login(P1, "Steve");
        ledger.quit(P1);
        assertFalse(ledger.isLoaded(P1));
        assertEquals(GemResult.NOT_LOADED, ledger.withdraw(P1, 1, "test"));
    }

    @Test
    void joinWithoutPreloadLoadsLater() throws Exception {
        online.add(P1);
        ledger.join(P1, "Steve"); // pre-login timed out: retry after join
        assertFalse(ledger.isLoaded(P1));
        settle();
        assertTrue(ledger.isLoaded(P1));
    }

    // ------------------------------------------------------------------ online changes

    @Test
    void depositAndWithdrawAreWrittenAsDeltas() throws Exception {
        login(P1, "Steve");
        assertEquals(GemResult.SUCCESS, ledger.deposit(P1, 50, "quest:mine-t3"));
        assertEquals(GemResult.SUCCESS, ledger.withdraw(P1, 20, "shopkeeper:test/t1"));
        assertEquals(30, ledger.balance(P1));
        settle();
        assertEquals(30, stored(P1));
        List<GemDatabase.TxRow> rows = log(P1);
        assertEquals(2, rows.size());
        assertEquals(-20, rows.get(0).delta());
        assertEquals(30, rows.get(0).balanceAfter());
        assertEquals("quest:mine-t3", rows.get(1).reason());
    }

    @Test
    void validationNeverChangesAnything() throws Exception {
        login(P1, "Steve");
        ledger.deposit(P1, 10, "test");
        assertEquals(GemResult.INSUFFICIENT_FUNDS, ledger.withdraw(P1, 11, "test"));
        assertEquals(GemResult.INVALID_AMOUNT, ledger.withdraw(P1, 0, "test"));
        assertEquals(GemResult.INVALID_AMOUNT, ledger.deposit(P1, -5, "test"));
        assertEquals(GemResult.INVALID_AMOUNT, ledger.deposit(P1, 5, " "));
        assertEquals(GemResult.BALANCE_LIMIT, ledger.deposit(P1, 1_000_000, "test"));
        assertEquals(GemResult.BALANCE_LIMIT, ledger.deposit(P1, Long.MAX_VALUE, "test"));
        assertEquals(10, ledger.balance(P1));
        settle();
        assertEquals(10, stored(P1));
        assertEquals(1, log(P1).size());
    }

    @Test
    void withdrawThenDepositRestoresExactly() throws Exception {
        login(P1, "Steve");
        ledger.deposit(P1, 1_000_000, "test"); // at the maximum
        assertEquals(GemResult.SUCCESS, ledger.withdraw(P1, 400, "shop"));
        assertEquals(GemResult.SUCCESS, ledger.deposit(P1, 400, "shop:rollback"));
        assertEquals(1_000_000, ledger.balance(P1));
    }

    @Test
    void setIsLoggedAsTheDifference() throws Exception {
        login(P1, "Steve");
        ledger.deposit(P1, 100, "test");
        GemLedger.Change change = ledger.set(P1, 40, "admin:console:set");
        assertEquals(GemResult.SUCCESS, change.result());
        assertEquals(-60, change.delta());
        assertEquals(GemResult.SUCCESS, ledger.set(P1, 40, "admin:console:set").result()); // no change, no row
        settle();
        assertEquals(40, stored(P1));
        assertEquals(2, log(P1).size());
    }

    @Test
    void payMovesBothSidesInOneTransaction() throws Exception {
        login(P1, "Alice");
        login(P2, "Bob");
        ledger.deposit(P1, 100, "test");
        assertEquals(GemLedger.PayResult.SUCCESS, ledger.pay(P1, P2, 30));
        assertEquals(70, ledger.balance(P1));
        assertEquals(30, ledger.balance(P2));
        settle();
        assertEquals(70, stored(P1));
        assertEquals(30, stored(P2));
        assertEquals("pay:to:" + P2, log(P1).get(0).reason());
        assertEquals("pay:from:" + P1, log(P2).get(0).reason());
    }

    @Test
    void payRefusals() throws Exception {
        login(P1, "Alice");
        ledger.deposit(P1, 10, "test");
        assertEquals(GemLedger.PayResult.TARGET_NOT_LOADED, ledger.pay(P1, P2, 5));
        login(P2, "Bob");
        assertEquals(GemLedger.PayResult.INSUFFICIENT_FUNDS, ledger.pay(P1, P2, 11));
        assertEquals(GemLedger.PayResult.INVALID_AMOUNT, ledger.pay(P1, P1, 5));
        ledger.deposit(P2, 999_995, "test");
        assertEquals(GemLedger.PayResult.TARGET_FULL, ledger.pay(P1, P2, 10));
        assertEquals(10, ledger.balance(P1));
        assertEquals(999_995, ledger.balance(P2));
    }

    // ------------------------------------------------------------------ offline changes and ordering (§3.4, GEM-13)

    @Test
    void offlineGiveIsSeenOnNextLogin() throws Exception {
        CompletableFuture<GemLedger.Change> give = ledger.changeStored(P1, "Steve", GemLedger.Action.GIVE, 10, "admin:console:give");
        settle();
        assertEquals(GemResult.SUCCESS, done(give).result());
        assertEquals(10, done(give).balance());
        login(P1, "Steve");
        assertEquals(10, ledger.balance(P1));
    }

    @Test
    void offlineTakeNeverGoesNegative() throws Exception {
        ledger.changeStored(P1, "Steve", GemLedger.Action.GIVE, 5, "t");
        CompletableFuture<GemLedger.Change> take = ledger.changeStored(P1, "Steve", GemLedger.Action.TAKE, 6, "t");
        settle();
        assertEquals(GemResult.INSUFFICIENT_FUNDS, done(take).result());
        assertEquals(5, stored(P1));
    }

    @Test
    void offlineChangeAfterPreLoginIsCountedOnce() throws Exception {
        online.add(P1);
        ledger.preLogin(P1, "Steve");                 // loaded into pending (balance 0)
        assertTrue(ledger.isPending(P1));
        ledger.changeStored(P1, "Steve", GemLedger.Action.GIVE, 10, "t"); // commits after the load
        settle();
        ledger.join(P1, "Steve");
        settle();
        assertEquals(10, ledger.balance(P1));
        assertEquals(10, stored(P1));
    }

    @Test
    void offlineChangeWhileOnlineButNotLoadedIsCountedOnce() throws Exception {
        online.add(P1);
        ledger.changeStored(P1, "Steve", GemLedger.Action.GIVE, 10, "t"); // queued before the load
        ledger.join(P1, "Steve");                                         // no pre-login: load queued after
        ledger.changeStored(P1, "Steve", GemLedger.Action.GIVE, 5, "t");  // queued after the load
        settle();
        assertEquals(15, ledger.balance(P1));
        assertEquals(15, stored(P1));
    }

    @Test
    void storedChangeOnLoadedPlayerIsMirroredOnce() throws Exception {
        login(P1, "Steve");
        ledger.deposit(P1, 3, "t");
        ledger.changeStored(P1, "Steve", GemLedger.Action.GIVE, 10, "t");
        settle();
        assertEquals(13, ledger.balance(P1));
        assertEquals(13, stored(P1));
        ledger.mirrorIntoOnline(P1, 10, 1); // an older transaction id is never applied again
        assertEquals(13, ledger.balance(P1));
    }

    // ------------------------------------------------------------------ durability and disabled mode

    @Test
    void closeDrainsTheQueue() throws Exception {
        login(P1, "Steve");
        for (int i = 0; i < 100; i++) ledger.deposit(P1, 1, "t");
        reopen();
        assertEquals(100, stored(P1));
        assertEquals(100, log(P1).size());
    }

    @Test
    void oneFailingWriteDoesNotLoseTheRestOfItsBatch() throws Exception {
        login(P1, "Steve");
        long now = clock.get();
        db.write("ok 1", c -> { GemDatabase.writeChange(c, P1, 5, 5, "t", now); return null; });
        db.write("no row", c -> { GemDatabase.writeChange(c, P2, 7, 7, "t", now); return null; }); // fails
        db.write("ok 2", c -> { GemDatabase.writeChange(c, P1, 3, 8, "t", now); return null; });
        settle();
        assertEquals(8, stored(P1));
        assertEquals(-1, stored(P2));
        assertEquals(2, log(P1).size());
    }

    @Test
    void writesAreCommittedBeforeLaterTasks() throws Exception {
        login(P1, "Steve");
        for (int i = 0; i < 2500; i++) ledger.deposit(P1, 1, "t"); // more than one batch
        assertEquals(2500L, (long) db.submit(c -> GemDatabase.balance(c, P1)).get(5, TimeUnit.SECONDS));
    }

    @Test
    void afterShutdownStaleApiReferencesRefuseEverything() throws Exception {
        login(P1, "Steve");
        ledger.deposit(P1, 10, "t");
        GemServiceImpl api = new GemServiceImpl(ledger, () -> true);
        ledger.shutdown();
        assertFalse(ledger.enabled());
        assertEquals(-1, api.balance(P1));
        assertEquals(GemResult.NOT_LOADED, api.withdraw(P1, 1, "t"));
        assertEquals(GemResult.NOT_LOADED, api.deposit(P1, 1, "t"));
        assertEquals(GemResult.NOT_LOADED,
                ledger.changeStored(P1, "Steve", GemLedger.Action.GIVE, 1, "t").join().result());
        settle();
        assertEquals(10, stored(P1)); // the write queued before the shutdown is kept
    }

    @Test
    void disabledLedgerReportsNotLoaded() throws Exception {
        GemLedger disabled = new GemLedger(() -> config, null, Runnable::run, id -> true, clock::get, LOG);
        assertFalse(disabled.enabled());
        disabled.preLogin(P1, "Steve");
        disabled.join(P1, "Steve");
        assertEquals(-1, disabled.balance(P1));
        assertEquals(GemResult.NOT_LOADED, disabled.deposit(P1, 1, "t"));
        assertEquals(GemResult.NOT_LOADED,
                disabled.changeStored(P1, "Steve", GemLedger.Action.GIVE, 1, "t").join().result());
    }

    @Test
    void apiRejectsCallsOffTheMainThread() throws Exception {
        login(P1, "Steve");
        GemServiceImpl api = new GemServiceImpl(ledger, () -> false);
        assertThrows(IllegalStateException.class, () -> api.deposit(P1, 1, "t"));
        assertThrows(IllegalStateException.class, () -> api.withdraw(P1, 1, "t"));
        assertEquals(0, api.balance(P1)); // reads are allowed on any thread
        GemServiceImpl main = new GemServiceImpl(ledger, () -> true);
        assertEquals(GemResult.SUCCESS, main.deposit(P1, 1, "t"));
        assertEquals("1.250", main.format(1250));
        assertEquals("Gem", main.displayName());
    }

    // ------------------------------------------------------------------ leaderboard

    @Test
    void leaderboardOrderAndExclusions() throws Exception {
        login(P1, "Alice");
        login(P2, "Admin");
        ledger.deposit(P1, 10, "t");
        ledger.deposit(P2, 99, "t");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("gem.top.exclude", List.of("admin"));
        config = GemConfig.parse(yaml, LOG);
        GemTop top = new GemTop(ledger, clock::get, LOG);
        top.refresh();
        settle();
        List<GemTop.Entry> entries = top.snapshot().entries();
        assertEquals(1, entries.size());
        assertEquals("Alice", entries.get(0).name());
        assertEquals(clock.get(), top.snapshot().builtAt());
    }

    @Test
    void pruneRemovesOldTransactionsOnly() throws Exception {
        login(P1, "Steve");
        ledger.deposit(P1, 1, "old");
        clock.addAndGet(10 * 86_400_000L);
        ledger.deposit(P1, 1, "new");
        settle();
        int removed = db.submit(c -> GemDatabase.prune(c, clock.get() - 86_400_000L)).get();
        assertEquals(1, removed);
        assertEquals(Map.of("new", 1L), Map.of(log(P1).get(0).reason(), log(P1).get(0).delta()));
        assertEquals(2, stored(P1)); // balances are never pruned
    }
}
