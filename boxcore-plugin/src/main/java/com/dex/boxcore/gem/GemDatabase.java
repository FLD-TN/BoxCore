package com.dex.boxcore.gem;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage of gems, {@code plugins/BoxCore/gems.db} (Update 03 §2, §3.1). One single-thread executor
 * ({@code BoxCore-Gem-DB}) owns the connection: every statement runs there, and the static SQL helpers below
 * must only be called from tasks given to {@link #submit} or {@link #write}.
 *
 * <p>Balance writes are <b>group-committed</b>: {@link #write} appends to a queue and one flush task on the DB
 * thread commits everything queued so far in one SQL transaction, so the throughput does not depend on one disk
 * sync per change. A write is always committed before any task submitted after it.
 */
public final class GemDatabase {

    /** A unit of work on the DB thread. */
    @FunctionalInterface
    public interface SqlTask<T> {
        T run(Connection connection) throws SQLException;
    }

    /** A loaded balance, tagged with the id of the last transaction the load saw (§3.4). */
    public record LoadResult(long balance, String name, long seenTx) {}

    public record TxRow(long at, long delta, long balanceAfter, String reason) {}

    public record TopEntry(String name, long balance) {}

    public static final String FILE = "gems.db";
    private static final long OPEN_TIMEOUT_SECONDS = 10;
    private static final long CLOSE_TIMEOUT_SECONDS = 10;
    /** Writes per SQL transaction at most, so one flush never holds the DB thread for long. */
    private static final int MAX_BATCH = 1000;
    /** Queued writes above this are reported (the disk cannot keep up), at most once per minute. */
    private static final int BACKLOG_WARNING = 20_000;
    private static final long BACKLOG_WARNING_INTERVAL_MS = 60_000L;

    /** One queued balance write; {@code what} names player, delta and reason for the failure log. */
    private record Write(String what, SqlTask<?> op) {}

    private final ExecutorService executor;
    private final Connection connection;
    private final Logger logger;
    private final ConcurrentLinkedQueue<Write> writes = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private volatile long lastBacklogWarning;

    private GemDatabase(ExecutorService executor, Connection connection, Logger logger) {
        this.executor = executor;
        this.connection = connection;
        this.logger = logger;
    }

    /**
     * Opens (or creates) the database and its tables. Returns {@code null} when the SQLite driver is missing
     * or the file cannot be used; the gem feature is then disabled. The file is never deleted or recreated.
     */
    public static GemDatabase open(File file, Logger logger) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException | LinkageError ex) {
            logger.severe("SQLite JDBC driver (org.sqlite.JDBC) not found: the gem feature is disabled, boosts keep working");
            return null;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BoxCore-Gem-DB");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Connection connection = executor.submit(() -> connect(file))
                    .get(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new GemDatabase(executor, connection, logger);
        } catch (Exception ex) {
            executor.shutdownNow();
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            logger.log(Level.SEVERE, "Could not open " + file.getName() + ": the gem feature is disabled until it is fixed "
                    + "(the file was not modified), boosts keep working", cause);
            return null;
        }
    }

    private static Connection connect(File file) throws SQLException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create " + parent);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            // WAL + NORMAL: a committed change survives a process crash or kill; only an OS crash or power loss
            // can drop the last commits (never corruption). FULL would sync the disk on every commit.
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS gem_balances (
                        uuid        TEXT    PRIMARY KEY,
                        name        TEXT    NOT NULL,
                        balance     INTEGER NOT NULL CHECK (balance >= 0),
                        updated_at  INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS gem_balances_balance ON gem_balances (balance DESC)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS gem_transactions (
                        id             INTEGER PRIMARY KEY AUTOINCREMENT,
                        at             INTEGER NOT NULL,
                        uuid           TEXT    NOT NULL,
                        delta          INTEGER NOT NULL,
                        balance_after  INTEGER NOT NULL,
                        reason         TEXT    NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS gem_transactions_uuid_at ON gem_transactions (uuid, at DESC)");
        } catch (SQLException ex) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // already failing
            }
            throw ex;
        }
        return connection;
    }

    /** Runs {@code task} on the DB thread. The future fails if the task throws or the database is closed. */
    public <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(task.run(connection));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException ex) {
            future.completeExceptionally(ex);
        }
        return future;
    }

    /**
     * Queues a balance write that nobody waits for; it is group-committed with the other queued writes. The
     * operation runs <b>inside</b> the batch transaction, so it must not manage transactions itself. A failure is
     * logged at SEVERE with {@code what}, which must name the player, the delta and the reason so the change can
     * be compensated manually (§3.3). Callable from any thread.
     */
    public void write(String what, SqlTask<?> op) {
        writes.add(new Write(what, op));
        int backlog = queued.incrementAndGet();
        if (backlog > BACKLOG_WARNING) {
            long now = System.currentTimeMillis();
            if (now - lastBacklogWarning > BACKLOG_WARNING_INTERVAL_MS) {
                lastBacklogWarning = now;
                logger.warning(backlog + " gem writes are waiting for the disk (" + FILE + " is too slow)");
            }
        }
        if (flushScheduled.compareAndSet(false, true)) {
            try {
                executor.execute(this::flush);
            } catch (RejectedExecutionException ex) {
                flushScheduled.set(false);
                failQueued("the database is closed");
            }
        }
    }

    /** DB thread: commits every queued write, in batches of at most {@link #MAX_BATCH}. */
    private void flush() {
        flushScheduled.set(false); // a write queued from now on schedules (or joins) another flush
        List<Write> batch = new ArrayList<>();
        while (true) {
            batch.clear();
            Write w;
            while (batch.size() < MAX_BATCH && (w = writes.poll()) != null) batch.add(w);
            if (batch.isEmpty()) return;
            queued.addAndGet(-batch.size());
            commit(batch);
        }
    }

    private void commit(List<Write> batch) {
        try {
            inTransaction(connection, c -> {
                for (Write w : batch) w.op().run(c);
                return null;
            });
        } catch (Throwable batchError) {
            // one bad write must not lose the others: retry them one by one
            for (Write w : batch) {
                try {
                    inTransaction(connection, c -> w.op().run(c));
                } catch (Throwable t) {
                    logger.log(Level.SEVERE, "Gem write failed, compensate manually: " + w.what(), t);
                }
            }
        }
    }

    private void failQueued(String why) {
        Write w;
        while ((w = writes.poll()) != null) {
            queued.decrementAndGet();
            logger.severe("Gem write lost (" + why + "), compensate manually: " + w.what());
        }
    }

    /** Stops accepting tasks, commits every queued write synchronously, then closes the connection (§3.5). */
    public void close() {
        executor.shutdown();
        boolean finished = false;
        try {
            finished = executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                logger.severe("Gem writes did not finish within " + CLOSE_TIMEOUT_SECONDS
                        + " s: the last queued changes may be lost");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.severe("Interrupted while waiting for gem writes: the last queued changes may be lost");
        }
        if (finished) {
            flush(); // writes queued after the last flush (the DB thread is gone, the connection is ours now)
        } else {
            failQueued("shutdown timeout");
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "Could not close " + FILE, ex);
        }
    }

    // ------------------------------------------------------------------ SQL helpers (DB thread only)

    /** Runs {@code task} in one SQL transaction; rolls back if it throws. */
    public static <T> T inTransaction(Connection c, SqlTask<T> task) throws SQLException {
        c.setAutoCommit(false);
        try {
            T result = task.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException ex) {
            try {
                c.rollback();
            } catch (SQLException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /**
     * Reads a player's row, creating it with {@code startingBalance} (and a {@code start} transaction when
     * it is above 0) if absent. Tagged with the id of the last transaction committed so far.
     */
    public static LoadResult load(Connection c, UUID uuid, String name, long startingBalance, long now)
            throws SQLException {
        return inTransaction(c, conn -> {
            Long balance = null;
            String stored = name;
            try (PreparedStatement ps = conn.prepareStatement("SELECT balance, name FROM gem_balances WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        balance = rs.getLong(1);
                        stored = rs.getString(2);
                    }
                }
            }
            if (balance == null) {
                balance = startingBalance;
                insertRow(conn, uuid, name, startingBalance, now);
                if (startingBalance > 0) insertTx(conn, uuid, now, startingBalance, startingBalance, "start");
            }
            return new LoadResult(balance, stored, maxTxId(conn));
        });
    }

    /** Id of the last committed transaction, 0 if none (ids are AUTOINCREMENT, never reused). */
    public static long maxTxId(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(id), 0) FROM gem_transactions")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public static void insertRow(Connection c, UUID uuid, String name, long balance, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO gem_balances (uuid, name, balance, updated_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, balance);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /** Balance stored for a player, {@code null} if there is no row. */
    public static Long balance(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM gem_balances WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** {@code balance = balance + delta}; fails (CHECK constraint) rather than going negative. */
    public static void addDelta(Connection c, UUID uuid, long delta, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE gem_balances SET balance = balance + ?, updated_at = ? WHERE uuid = ?")) {
            ps.setLong(1, delta);
            ps.setLong(2, now);
            ps.setString(3, uuid.toString());
            if (ps.executeUpdate() != 1) throw new SQLException("No gem_balances row for " + uuid);
        }
    }

    /** Appends one transaction row; returns its id. */
    public static long insertTx(Connection c, UUID uuid, long at, long delta, long balanceAfter, String reason)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO gem_transactions (at, uuid, delta, balance_after, reason) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, at);
            ps.setString(2, uuid.toString());
            ps.setLong(3, delta);
            ps.setLong(4, balanceAfter);
            ps.setString(5, reason);
            ps.executeUpdate();
        }
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** One balance change: delta update and its log row (run inside a {@link #write} batch, §3.3). */
    public static void writeChange(Connection c, UUID uuid, long delta, long balanceAfter, String reason, long now)
            throws SQLException {
        addDelta(c, uuid, delta, now);
        insertTx(c, uuid, now, delta, balanceAfter, reason);
    }

    public static void updateName(Connection c, UUID uuid, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE gem_balances SET name = ? WHERE uuid = ?")) {
            ps.setString(1, name);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Newest first. */
    public static List<TxRow> recent(Connection c, UUID uuid, int limit) throws SQLException {
        List<TxRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT at, delta, balance_after, reason FROM gem_transactions WHERE uuid = ? ORDER BY at DESC, id DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new TxRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4)));
            }
        }
        return rows;
    }

    /** Highest balances first (uses the {@code balance DESC} index). */
    public static List<TopEntry> top(Connection c, int limit) throws SQLException {
        List<TopEntry> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name, balance FROM gem_balances ORDER BY balance DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new TopEntry(rs.getString(1), rs.getLong(2)));
            }
        }
        return rows;
    }

    /** Deletes transactions older than {@code before} (epoch ms); returns the number of rows removed. */
    public static int prune(Connection c, long before) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM gem_transactions WHERE at < ?")) {
            ps.setLong(1, before);
            return ps.executeUpdate();
        }
    }
}
