package com.dex.boxcore.boost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory global and personal boosts (§5, §6, §8). Holds only active boosts, for online and offline
 * players alike, because expiry is real time. Main thread only.
 */
public final class BoostRegistry {

    private final Map<String, GlobalBoost> globals = new LinkedHashMap<>();
    private final Map<UUID, List<PersonalBoost>> personal = new HashMap<>();
    private Runnable onChange = () -> {};
    /** Earliest personal expiry instant, lets the tick skip the scan when nothing can have expired. */
    private long nextPersonalExpiry = Long.MAX_VALUE;

    /** Called after every change (used to mark storage dirty). */
    public void onChange(Runnable onChange) {
        this.onChange = onChange;
    }

    // ------------------------------------------------------------------ global

    public GlobalBoost global(String type) {
        return globals.get(type);
    }

    public Map<String, GlobalBoost> globals() {
        return Collections.unmodifiableMap(globals);
    }

    /** Starts or replaces the global boost of its type. @return the replaced boost, or {@code null} */
    public GlobalBoost putGlobal(GlobalBoost boost) {
        GlobalBoost old = globals.put(boost.type(), boost);
        onChange.run();
        return old;
    }

    /** @return the removed boost, or {@code null} if none was active */
    public GlobalBoost removeGlobal(String type) {
        GlobalBoost old = globals.remove(type);
        if (old != null) onChange.run();
        return old;
    }

    // ------------------------------------------------------------------ personal

    /** Read-only view of a player's personal boosts (all types), empty if none. */
    public List<PersonalBoost> personal(UUID player) {
        List<PersonalBoost> list = personal.get(player);
        return list == null ? List.of() : Collections.unmodifiableList(list);
    }

    /**
     * Stores the player's bottle of a type, deleting any previous bottle of that type (at most one per type,
     * Update 01 U1/U2).
     *
     * @return the deleted bottle (the highest one if legacy data held several), or {@code null} if none
     */
    public PersonalBoost setPersonal(UUID player, PersonalBoost boost) {
        List<PersonalBoost> list = personal.computeIfAbsent(player, k -> new ArrayList<>(2));
        PersonalBoost replaced = null;
        for (Iterator<PersonalBoost> it = list.iterator(); it.hasNext(); ) {
            PersonalBoost old = it.next();
            if (!old.type().equals(boost.type())) continue;
            it.remove();
            if (replaced == null || old.multiplier() > replaced.multiplier()) replaced = old;
        }
        list.add(boost);
        nextPersonalExpiry = Math.min(nextPersonalExpiry, boost.expiresAt());
        onChange.run();
        return replaced;
    }

    /** Read-only view of all personal boosts, for storage snapshots. */
    public Map<UUID, List<PersonalBoost>> allPersonal() {
        return Collections.unmodifiableMap(personal);
    }

    // ------------------------------------------------------------------ expiry

    /** Removes expired global boosts. @return the removed boosts, empty if none */
    public List<GlobalBoost> expireGlobals(long now) {
        List<GlobalBoost> expired = null;
        for (Iterator<GlobalBoost> it = globals.values().iterator(); it.hasNext(); ) {
            GlobalBoost b = it.next();
            if (b.isExpired(now)) {
                it.remove();
                if (expired == null) expired = new ArrayList<>(1);
                expired.add(b);
            }
        }
        if (expired == null) return List.of();
        onChange.run();
        return expired;
    }

    /** Removes expired personal boosts of every player. @return removed boosts per player, empty if none */
    public Map<UUID, List<PersonalBoost>> expirePersonal(long now) {
        if (now < nextPersonalExpiry) return Map.of();
        Map<UUID, List<PersonalBoost>> expired = new HashMap<>();
        long next = Long.MAX_VALUE;
        for (Iterator<Map.Entry<UUID, List<PersonalBoost>>> it = personal.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, List<PersonalBoost>> entry = it.next();
            List<PersonalBoost> list = entry.getValue();
            for (Iterator<PersonalBoost> li = list.iterator(); li.hasNext(); ) {
                PersonalBoost b = li.next();
                if (b.isExpired(now)) {
                    li.remove();
                    expired.computeIfAbsent(entry.getKey(), k -> new ArrayList<>(1)).add(b);
                } else {
                    next = Math.min(next, b.expiresAt());
                }
            }
            if (list.isEmpty()) it.remove();
        }
        nextPersonalExpiry = next;
        if (!expired.isEmpty()) onChange.run();
        return expired;
    }

    // ------------------------------------------------------------------ storage

    /**
     * Replaces the whole content with loaded data (startup only). Does not fire the change callback.
     * Keeps at most one bottle per player and type (1.0.0 data could hold several): the highest multiplier,
     * tie broken by the later expiry.
     *
     * @return the number of personal boosts dropped by that rule
     */
    public int load(Map<String, GlobalBoost> loadedGlobals, Map<UUID, List<PersonalBoost>> loadedPersonal) {
        globals.clear();
        personal.clear();
        globals.putAll(loadedGlobals);
        long next = Long.MAX_VALUE;
        int dropped = 0;
        for (Map.Entry<UUID, List<PersonalBoost>> e : loadedPersonal.entrySet()) {
            Map<String, PersonalBoost> perType = new LinkedHashMap<>();
            for (PersonalBoost b : e.getValue()) {
                PersonalBoost kept = perType.get(b.type());
                if (kept == null || b.multiplier() > kept.multiplier()
                        || (b.multiplier() == kept.multiplier() && b.expiresAt() > kept.expiresAt())) {
                    perType.put(b.type(), b);
                }
            }
            dropped += e.getValue().size() - perType.size();
            if (perType.isEmpty()) continue;
            personal.put(e.getKey(), new ArrayList<>(perType.values()));
            for (PersonalBoost b : perType.values()) next = Math.min(next, b.expiresAt());
        }
        nextPersonalExpiry = next;
        return dropped;
    }
}
