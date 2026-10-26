package com.dex.boxcore.item;

import com.dex.boxcore.api.ClanBoostResult;
import com.dex.boxcore.boost.BoostController;
import com.dex.boxcore.boost.ClanLink;
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
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bottle use (§6.3). Any item carrying {@code boost_type} never gets vanilla behaviour; activation happens
 * on the configured click with the main hand only, in one tick on the main thread, consuming before granting.
 *
 * <p>Update 05: a bottle of the same multiplier as the running one adds its duration (E5), a lower one is refused and
 * kept; clan bottles start the boost of the user's clan (E3.3). Honey bottles and water bottles cannot be drunk,
 * crafted or brewed (E1).
 */
public final class BottleListener implements Listener {

    /**
     * Why a bottle needs a second use; each case has its own warning. Only a second use for the same reason
     * confirms.
     */
    private enum Warning {
        /** Same multiplier, the added time would be cut at max-duration. */
        CAPPED("bottle.confirm-capped"),
        /** Higher multiplier: replaces the running bottle, its remaining time is lost. */
        HIGHER("bottle.confirm-higher"),
        /** No running bottle, but the rank, global or clan boost is already as high: consumed without effect. */
        NO_EFFECT("bottle.confirm-no-effect"),
        /** Clan bottle with a higher multiplier than the clan's running boost. */
        CLAN_REPLACE("clan.confirm-replace"),
        /** Clan bottle of the same multiplier, cut at max-duration. */
        CLAN_CAPPED("clan.confirm-capped");

        final String key;

        Warning(String key) {
            this.key = key;
        }
    }

    /** A warned-about bottle waiting for a second use inside the confirmation window. */
    private record PendingConfirm(BottleData data, Warning warning, long timestamp) {
        boolean matches(BottleData other, Warning current, long now, long windowMs) {
            return data.equals(other) && warning == current && now - timestamp < windowMs;
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
            // never any vanilla behaviour: no throw, no drink, no water fill, no container, no block break start
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

    // ------------------------------------------------------------------ vanilla uses of honey / water bottles (E1)

    /** A honey bottle or a water bottle is never drunk. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        try {
            if (reader.isBottle(event.getItem())) event.setCancelled(true);
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("consume", t);
        }
    }

    /** No crafting result with a bottle in the grid (honey bottle → sugar, honey block). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        try {
            if (anyBottle(event.getInventory().getMatrix())) event.getInventory().setResult(null);
        } catch (Throwable t) {
            event.getInventory().setResult(null);
            errors.report("prepare craft", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        try {
            if (anyBottle(event.getInventory().getMatrix())) event.setCancelled(true);
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("craft", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        try {
            if (event.getBlock().getState(false) instanceof Crafter crafter
                    && anyBottle(crafter.getInventory().getContents())) {
                event.setCancelled(true);
            }
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("crafter", t);
        }
    }

    /** A water bottle is a brewing ingredient: never brewed. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        try {
            if (anyBottle(event.getContents().getContents())) event.setCancelled(true);
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("brew", t);
        }
    }

    /** Bottles cannot be put into a brewing stand: click, shift-click or number key. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewerClick(InventoryClickEvent event) {
        try {
            Inventory top = event.getView().getTopInventory();
            if (!(top instanceof BrewerInventory)) return;
            boolean intoTop = event.getClickedInventory() == top;
            boolean placed = intoTop && (reader.isBottle(event.getCursor())
                    || (event.getHotbarButton() >= 0
                        && reader.isBottle(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))));
            boolean shifted = !intoTop && event.isShiftClick() && reader.isBottle(event.getCurrentItem());
            if (placed || shifted) event.setCancelled(true);
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("brewing stand click", t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewerDrag(InventoryDragEvent event) {
        try {
            Inventory top = event.getView().getTopInventory();
            if (!(top instanceof BrewerInventory) || !reader.isBottle(event.getOldCursor())) return;
            for (int slot : event.getRawSlots()) {
                if (slot < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("brewing stand drag", t);
        }
    }

    /** Hoppers do not feed bottles into a brewing stand. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        try {
            if (event.getDestination() instanceof BrewerInventory && reader.isBottle(event.getItem())) {
                event.setCancelled(true);
            }
        } catch (Throwable t) {
            event.setCancelled(true);
            errors.report("hopper move", t);
        }
    }

    private boolean anyBottle(ItemStack[] items) {
        if (items == null) return false;
        for (ItemStack item : items) if (reader.isBottle(item)) return true;
        return false;
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

    // ------------------------------------------------------------------ use

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

        long now = System.currentTimeMillis();
        if (data.clan()) useClan(player, hand, type, data, now);
        else usePersonal(player, hand, type, data, now);
    }

    /** True when this use confirms the warning shown by the previous one (same bottle, same reason, in time). */
    private boolean confirms(UUID id, BottleData data, Warning warning, long now) {
        PendingConfirm confirm = pending.get(id);
        return confirm != null && confirm.matches(data, warning, now, config.get().confirmWindowSeconds() * 1000L);
    }

    private void warn(Player player, BottleData data, Warning warning, long now, BoostType type, Object... placeholders) {
        pending.put(player.getUniqueId(), new PendingConfirm(data, warning, now));
        Messages msg = messages.get();
        Object[] all = new Object[placeholders.length + 6];
        all[0] = "type";
        all[1] = type.id();
        all[2] = "type_name";
        all[3] = type.displayName();
        all[4] = "seconds";
        all[5] = config.get().confirmWindowSeconds();
        System.arraycopy(placeholders, 0, all, 6, placeholders.length);
        msg.send(player, type, warning.key, all);
    }

    private void usePersonal(Player player, ItemStack hand, BoostType type, BottleData data, long now) {
        Messages msg = messages.get();
        UUID id = player.getUniqueId();
        EffectiveCache.Snapshot snapshot = controller.refresh(player, now);
        String t = type.id();
        int current = snapshot.effective(t);
        int active = snapshot.bottle(t);
        int m = data.tenths();
        int rank = snapshot.rank(t);
        int global = snapshot.global(t);
        int clan = snapshot.clan(t);
        PersonalBoost running = MultiplierCalculator.displayed(controller.registry().personal(id), t, now);
        String remaining = running == null ? "" : DurationFormat.clock(DurationFormat.remainingSeconds(running.expiresAt(), now));

        // E5: same multiplier adds the duration, up to max-duration
        if (running != null && m == active) {
            MultiplierCalculator.Extension e = MultiplierCalculator.extend(running.expiresAt(),
                    data.durationSeconds() * 1000L, now, type.maxDurationSeconds() * 1000L);
            if (e.full()) {
                pending.remove(id);
                msg.send(player, type, "bottle.full", "type", t, "type_name", type.displayName(),
                        "multiplier", msg.multiplier(m), "max", msg.duration(type.maxDurationSeconds()));
                return;
            }
            if (e.lostMs() > 0 && !confirms(id, data, Warning.CAPPED, now)) {
                warn(player, data, Warning.CAPPED, now, type, "multiplier", msg.multiplier(m),
                        "max", msg.duration(type.maxDurationSeconds()), "lost", msg.duration((e.lostMs() + 999) / 1000),
                        "remaining", remaining);
                return;
            }
            pending.remove(id);
            consumeOne(player.getInventory(), hand);
            controller.extendPersonal(player, running, e.expiresAt(), now);
            msg.notify(player, type, "bottle.extended", "type", t, "type_name", type.displayName(),
                    "multiplier", msg.multiplier(m), "added", msg.duration((e.expiresAt() - running.expiresAt()) / 1000),
                    "remaining", msg.duration(DurationFormat.remainingSeconds(e.expiresAt(), now)));
            playSuccess(player);
            return;
        }
        // E5: a lower bottle is refused and kept
        if (running != null && m < active) {
            pending.remove(id);
            msg.send(player, type, "bottle.refused-lower", "type", t, "type_name", type.displayName(),
                    "multiplier", msg.multiplier(m), "old", msg.multiplier(active), "remaining", remaining);
            return;
        }

        boolean useful = MultiplierCalculator.isUseful(rank, active, global, clan, m, type.maxTenths());
        Warning warning = useful ? (active > 0 ? Warning.HIGHER : null) : Warning.NO_EFFECT;
        if (warning == null) {
            pending.remove(id);
            activate(player, hand, type, data, now);
            return;
        }
        if (!confirms(id, data, warning, now)) {
            int after = MultiplierCalculator.effective(rank, m, global, clan, type.maxTenths());
            warn(player, data, warning, now, type, "multiplier", msg.multiplier(m), "old", msg.multiplier(active),
                    "remaining", remaining, "duration", msg.duration(data.durationSeconds()),
                    "effective", msg.multiplier(current), "after", msg.multiplier(after));
            return;
        }
        // second use inside the window: the player's informed choice
        pending.remove(id);
        if (warning == Warning.HIGHER) {
            activate(player, hand, type, data, now);
            return;
        }
        // no effect: consumed, the running bottle of the type (if any) is kept unchanged
        consumeOne(player.getInventory(), hand);
        msg.notify(player, type, "bottle.used-no-effect", "type", t, "type_name", type.displayName(),
                "multiplier", msg.multiplier(m));
    }

    /** E3.3: Minh Chủ / Phó Bang start, extend or replace their clan's boost; the bottle is used only on success. */
    private void useClan(Player player, ItemStack hand, BoostType type, BottleData data, long now) {
        Messages msg = messages.get();
        UUID id = player.getUniqueId();
        ClanLink clans = controller.clans();
        if (!clans.available()) {
            msg.send(player, "clan.unavailable");
            return;
        }
        UUID clan = clans.clanOf(id);
        if (clan == null) {
            msg.send(player, "clan.no-clan");
            return;
        }
        if (!clans.canUse(id)) {
            msg.send(player, "clan.not-allowed");
            return;
        }
        String t = type.id();
        int m = data.tenths();
        ClanBoostResult result = controller.startClan(type, clan, m, data.durationSeconds(), false, player.getName(), now);
        Warning warning = switch (result.status()) {
            case CONFIRM_REPLACE -> Warning.CLAN_REPLACE;
            case CONFIRM_CAPPED -> Warning.CLAN_CAPPED;
            default -> null;
        };
        if (warning != null) {
            if (!confirms(id, data, warning, now)) {
                warn(player, data, warning, now, type, "multiplier", msg.multiplier(m),
                        "old", msg.multiplier(result.oldTenths()),
                        "remaining", DurationFormat.clock(DurationFormat.remainingSeconds(result.oldExpiresAt(), now)),
                        "max", msg.duration(type.maxDurationSeconds()), "lost", msg.duration(result.lostSeconds()));
                return;
            }
            result = controller.startClan(type, clan, m, data.durationSeconds(), true, player.getName(), now);
        }
        pending.remove(id);
        switch (result.status()) {
            case STARTED, EXTENDED, REPLACED -> {
                consumeOne(player.getInventory(), hand);
                playSuccess(player); // the clan (this player included) was told by the controller
            }
            case LOWER -> msg.send(player, type, "clan.refused-lower", "type", t, "type_name", type.displayName(),
                    "multiplier", msg.multiplier(m), "old", msg.multiplier(result.oldTenths()));
            case FULL -> msg.send(player, type, "clan.full", "type", t, "type_name", type.displayName(),
                    "multiplier", msg.multiplier(m), "max", msg.duration(type.maxDurationSeconds()));
            case INVALID -> msg.send(player, "bottle.invalid");
            default -> msg.send(player, "clan.unavailable");
        }
    }

    /** Consumes the bottle, then replaces the active bottle of the type (if any). */
    private void activate(Player player, ItemStack hand, BoostType type, BottleData data, long now) {
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
        playSuccess(player);
    }

    private void playSuccess(Player player) {
        CoreConfig cfg = config.get();
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
