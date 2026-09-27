package com.dex.boxcore.task;

import com.dex.boxcore.boost.BoostController;
import com.dex.boxcore.util.ErrorReporter;

/**
 * The single repeating task (every 20 ticks) for the whole server (§7.2): removes expired global and
 * personal boosts, recomputes affected players, and updates bossbar texts.
 */
public final class TickTask implements Runnable {

    public static final long PERIOD_TICKS = 20L;

    private final BoostController controller;
    private final ErrorReporter errors;

    public TickTask(BoostController controller, ErrorReporter errors) {
        this.controller = controller;
        this.errors = errors;
    }

    @Override
    public void run() {
        try {
            controller.tick(System.currentTimeMillis());
        } catch (Throwable t) {
            errors.report("tick task", t);
        }
    }
}
