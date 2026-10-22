package com.dex.boxcore.task;

import com.dex.boxcore.boost.BoostController;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Periodic re-read of rank permissions (§7.1), spread over the period: runs every tick and refreshes a few players
 * per tick in turn, so every online player is refreshed once per {@code permission-refresh-seconds} without one
 * big tick that reads every player's effective permissions at once (LuckPerms builds a new set on each read).
 * Main thread only.
 */
public final class PermissionRefreshTask implements Runnable {

    private final BoostController controller;
    private final IntSupplier periodSeconds;
    private final ErrorReporter errors;
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private int perTick;
    private int ticksLeft;

    public PermissionRefreshTask(BoostController controller, IntSupplier periodSeconds, ErrorReporter errors) {
        this.controller = controller;
        this.periodSeconds = periodSeconds;
        this.errors = errors;
    }

    @Override
    public void run() {
        try {
            if (ticksLeft <= 0) startCycle();
            ticksLeft--;
            long now = System.currentTimeMillis();
            for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
                Player player = Bukkit.getPlayer(queue.poll());
                if (player == null) continue; // left since the cycle started
                try {
                    controller.refresh(player, now);
                } catch (Throwable t) {
                    errors.report("refreshing " + player.getName(), t);
                }
            }
        } catch (Throwable t) {
            errors.report("permission refresh", t);
        }
    }

    /** Queues every online player; the period is read each cycle, so a reload applies from the next cycle. */
    private void startCycle() {
        queue.clear();
        for (Player player : Bukkit.getOnlinePlayers()) queue.add(player.getUniqueId());
        int periodTicks = Math.max(1, periodSeconds.getAsInt() * 20);
        ticksLeft = periodTicks;
        perTick = perTick(queue.size(), periodTicks);
    }

    /** Players per tick so that {@code players} are all refreshed within {@code periodTicks}: rounded up. */
    static int perTick(int players, int periodTicks) {
        return (players + periodTicks - 1) / periodTicks;
    }
}
