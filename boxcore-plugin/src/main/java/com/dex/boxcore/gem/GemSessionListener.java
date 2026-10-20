package com.dex.boxcore.gem;

import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loads balances before login and moves them into the online cache on join (Update 03 §3.2). */
public final class GemSessionListener implements Listener {

    private final GemLedger ledger;
    private final ErrorReporter errors;
    /** Last successful {@code /gem pay} per sender (§5.2 step 5), main thread only; cleared on quit. */
    private final Map<UUID, Long> payCooldowns;

    public GemSessionListener(GemLedger ledger, Map<UUID, Long> payCooldowns, ErrorReporter errors) {
        this.ledger = ledger;
        this.payCooldowns = payCooldowns;
        this.errors = errors;
    }

    public static Map<UUID, Long> newCooldownMap() {
        return new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            ledger.preLogin(event.getUniqueId(), event.getName());
        } catch (Throwable t) {
            errors.report("gem pre-login load", t);
        }
    }

    /** LOWEST so other plugins' join handlers already see the balance. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        try {
            ledger.join(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        } catch (Throwable t) {
            errors.report("gem join", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        try {
            UUID id = event.getPlayer().getUniqueId();
            ledger.quit(id);
            payCooldowns.remove(id);
        } catch (Throwable t) {
            errors.report("gem quit", t);
        }
    }
}
