package com.dex.boxcore.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Logs each distinct error at most once per minute (§11). */
public final class ErrorReporter {

    private static final long INTERVAL_MS = 60_000L;
    private static final int MAX_KEYS = 256;

    private final Logger logger;
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    public ErrorReporter(Logger logger) {
        this.logger = logger;
    }

    public void report(String context, Throwable t) {
        StackTraceElement[] trace = t.getStackTrace();
        String key = context + '|' + t.getClass().getName() + '|' + (trace.length > 0 ? trace[0] : "");
        if (!shouldLog(key)) return;
        logger.log(Level.SEVERE, "Error in " + context + " (further identical errors are suppressed for 1 minute)", t);
    }

    public void warn(String key, String message) {
        if (!shouldLog(key)) return;
        logger.warning(message);
    }

    private boolean shouldLog(String key) {
        long now = System.currentTimeMillis();
        if (lastLogged.size() > MAX_KEYS) lastLogged.clear();
        Long prev = lastLogged.get(key);
        if (prev != null && now - prev < INTERVAL_MS) return false;
        lastLogged.put(key, now);
        return true;
    }
}
