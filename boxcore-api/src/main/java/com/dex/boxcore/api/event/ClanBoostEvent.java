package com.dex.boxcore.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * A clan's boost changed (Update 05 E3): started, extended, replaced, stopped by staff, or expired. Fired on the main
 * thread after the change, whatever started it (a bottle in hand, the clan plugin's storage, a command); it cannot be
 * cancelled. The clan plugin uses it for its history.
 *
 * @since 1.3.0
 */
public class ClanBoostEvent extends Event {

    public enum Action { STARTED, EXTENDED, REPLACED, STOPPED, EXPIRED }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID clan;
    private final String type;
    private final Action action;
    private final int tenths;
    private final int oldTenths;
    private final long expiresAt;
    private final long addedSeconds;
    private final String actor;

    public ClanBoostEvent(UUID clan, String type, Action action, int tenths, int oldTenths, long expiresAt,
                          long addedSeconds, String actor) {
        this.clan = clan;
        this.type = type;
        this.action = action;
        this.tenths = tenths;
        this.oldTenths = oldTenths;
        this.expiresAt = expiresAt;
        this.addedSeconds = addedSeconds;
        this.actor = actor;
    }

    public UUID clan() {
        return clan;
    }

    /** Boost type id. */
    public String type() {
        return type;
    }

    public Action action() {
        return action;
    }

    /** Multiplier in tenths of the boost (after the change; the ended one for STOPPED / EXPIRED). */
    public int tenths() {
        return tenths;
    }

    /** REPLACED: multiplier of the replaced boost, otherwise 0. */
    public int oldTenths() {
        return oldTenths;
    }

    /** Expiry after the change, epoch milliseconds (STOPPED / EXPIRED: when it ended). */
    public long expiresAt() {
        return expiresAt;
    }

    /** STARTED / REPLACED: the duration; EXTENDED: the time actually added; otherwise 0. */
    public long addedSeconds() {
        return addedSeconds;
    }

    /** Who did it (a player name), {@code null} for staff or when it expired. */
    public String actor() {
        return actor;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
