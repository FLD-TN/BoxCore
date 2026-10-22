package com.dex.boxcore.item;

import com.dex.boxcore.boost.BoostController;
import com.dex.boxcore.boost.EffectiveCache;
import com.dex.boxcore.boost.MultiplierCalculator;
import com.dex.boxcore.boost.PersonalBoost;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.util.DurationFormat;
import com.dex.boxcore.util.ErrorReporter;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bottle use (§6.3). Any item carrying {@code boost_type} never gets vanilla behaviour; activation happens
 * on the configured click with the main hand only, in one tick on the main thread, consuming before granting.
 */
public final class BottleListener implements Listener {

    /**
     * Why a bottle needs a second use; each case has its own warning ({@code bottle.confirm-<case>}).
     * SAME and HIGHER replace the active bottle once confirmed, LOWER and NO_EFFECT are consumed without effect.
     */
    private enum Warning {
        SAME("bottle.confirm-same", true),
        HIGHER("bottle.confirm-higher", true),
        LOWER("bottle.confirm-lower", false),
        NO_EFFECT("bottle.confirm-no-effect", false);

        final String key;
        final boolean replaces;

        Warning(String key, boolean replaces) {
            this.key = key;
            this.replaces = replaces;
        }
    }

    /** A warned-about bottle waiting for a second use inside the confirmation window. */
    private record PendingConfirm(String type, int tenths, long durationSeconds, Warning warning, long timestamp) {
        boolean matches(BottleData data, Warning current) {
            return type.equals(data.type()) && tenths == data.tenths() && durationSeconds == data.durationSeconds()
                    && warning == current;
        }
    }

    private final Supplier<CoreConfig> config;
    private final Supplier<Messages> messages;
    private final BoostController controller;
    private final BoostItemReader reader;
    private final ErrorReporter errors;
    private final Map<UUID, PendingConfirm> pending = new HashMap<>();
    private final Map<UUID, Integer> lastUseTick = new HashMap<>();

    public BottleListener(Supplier<CoreConfig> config, Supplier<Messages> messages, BoostController controller,
                          BoostItemReader reader, ErrorReporter errors) {
        this.config = config;
        this.messages = messages;
        this.controller = controller;
        this.reader = reader;
        this.errors = errors;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        try {
            Action action = event.getAction();
            if (action == Action.PHYSICAL) return;
            if (!reader.isBottle(event.getItem())) return;
            // never any vanilla behaviour: no throw, no water fill, no container, no block break start
            deny(event);
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (!matchesClick(action, config.get().useClick())) return;
            use(event.getPlayer());
        } catch (Throwable t) {
            errors.report("bottle use", t);
            try {
                deny(event);
                messages.get().send(event.getPlayer(), "command.error");
            } catch (Throwable ignored) {
                // nothing escapes to Bukkit
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        try {
            if (config.get().useClick() != CoreConfig.UseClick.LEFT) return;
            if (reader.isBottle(event.getItemInHand())) event.setCancelled(true);
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("block damage", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        try {
            if (reader.isBottle(event.getItem())) event.setCancelled(true);
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("dispense", t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        try {
            UUID id = event.getPlayer().getUniqueId();
            pending.remove(id);
            lastUseTick.remove(id);
        } catch (Throwable t) {
            errors.report("player quit", t);
        }
    }

    private static void deny(PlayerInteractEvent event) {
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
    }

    private static boolean matchesClick(Action action, CoreConfig.UseClick click) {
        return click == CoreConfig.UseClick.LEFT
                ? action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK
                : action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    private void use(Player player) {
        CoreConfig cfg = config.get();
        Messages msg = messages.get();
        UUID id = player.getUniqueId();

        // 1. cooldown: ignore silently
        int tick = Bukkit.getCurrentTick();
        Integer last = lastUseTick.get(id);
        if (last != null && tick - last < cfg.useCooldownTicks()) return;
        lastUseTick.put(id, tick);

        // 2. re-validate PDC data against the current config
        ItemStack hand = player.getInventory().getItemInMainHand();
        BottleData data = reader.read(hand);
        BoostType type = BoostItemReader.validate(data, cfg);
        if (type == null) {
            msg.send(player, "bottle.invalid");
            return;
        }

        // 3. game mode
        if (player.getGameMode() == GameMode.SPECTATOR) {
            msg.send(player, "bottle.gamemode");
            return;
        }

        // 4. current = effective now; after = effective as if this bottle were the only bottle of the type
        long now = System.currentTimeMillis();
        EffectiveCache.Snapshot snapshot = controller.refresh(player, now);
        int current = snapshot.effective(type.id());
        int active = snapshot.bottle(type.id());
        int m = data.tenths();
        int after = MultiplierCalculator.effective(snapshot.rank(type.id()), m, snapshot.global(type.id()),
                type.maxTenths());

        boolean useful = MultiplierCalculator.isUseful(snapshot.rank(type.id()), active, snapshot.global(type.id()), m,
                type.maxTenths());
        Warning warning;
        if (MultiplierCalculator.isRenewal(active, m)) {
            warning = Warning.SAME;                       // the active bottle's remaining time would be lost
        } else if (useful) {
            warning = active > 0 ? Warning.HIGHER : null; // replaces a running bottle, or nothing to lose
        } else {
            warning = active > m ? Warning.LOWER : Warning.NO_EFFECT;
        }

        if (warning == null) {
            pending.remove(id);
            activate(player, hand, type, data, now);
            return;
        }

        // Every other case warns first; only a use of the same bottle, for the same reason, within the window
        // confirms. Later, the window restarts with a new warning.
        long windowMs = cfg.confirmWindowSeconds() * 1000L;
        PendingConfirm confirm = pending.get(id);
        if (confirm == null || !confirm.matches(data, warning) || now - confirm.timestamp() >= windowMs) {
            pending.put(id, new PendingConfirm(type.id(), m, data.durationSeconds(), warning, now));
            PersonalBoost old = MultiplierCalculator.displayed(controller.registry().personal(id), type.id(), now);
            String remaining = old == null ? "" : DurationFormat.clock(DurationFormat.remainingSeconds(old.expiresAt(), now));
            msg.send(player, type, warning.key, "type", type.id(), "type_name", type.displayName(),
                    "multiplier", msg.multiplier(m), "old", msg.multiplier(active), "remaining", remaining,
                    "duration", msg.duration(data.durationSeconds()),
                    "effective", msg.multiplier(current), "after", msg.multiplier(after),
                    "seconds", cfg.confirmWindowSeconds());
            return;
        }
        // second use inside the window: the player's informed choice
        pending.remove(id);
        if (warning.replaces) {
            activate(player, hand, type, data, now);
            return;
        }
        // no effect: consumed, the active bottle of the type is kept unchanged
        consumeOne(player.getInventory(), hand);
        msg.notify(player, type, "bottle.used-no-effect", "type", type.id(), "type_name", type.displayName(),
                "multiplier", msg.multiplier(m));
    }

    /** Consumes the bottle, then replaces the active bottle of the type (if any). */
    private void activate(Player player, ItemStack hand, BoostType type, BottleData data, long now) {
        CoreConfig cfg = config.get();
        Messages msg = messages.get();
        int m = data.tenths();
        consumeOne(player.getInventory(), hand);
        PersonalBoost replaced = controller.setPersonal(player, new PersonalBoost(type.id(), m,
                now + data.durationSeconds() * 1000L, data.durationSeconds()), now);
        if (replaced != null) {
            msg.notify(player, type, "bottle.replaced", "type", type.id(), "type_name", type.displayName(),
                    "old", msg.multiplier(replaced.tenths()), "multiplier", msg.multiplier(m),
                    "remaining", DurationFormat.clock(DurationFormat.remainingSeconds(replaced.expiresAt(), now)));
        }
        msg.notify(player, type, "bottle.used", "type", type.id(), "type_name", type.displayName(),
                "multiplier", msg.multiplier(m), "duration", msg.duration(data.durationSeconds()));
        if (cfg.successSound() != null) {
            player.playSound(Sound.sound(cfg.successSound(), Sound.Source.PLAYER, 1f, 1f), Sound.Emitter.self());
        }
    }

    private static void consumeOne(PlayerInventory inventory, ItemStack hand) {
        int amount = hand.getAmount();
        if (amount <= 1) {
            inventory.setItemInMainHand(null);
        } else {
            hand.setAmount(amount - 1);
            inventory.setItemInMainHand(hand);
        }
    }
}
