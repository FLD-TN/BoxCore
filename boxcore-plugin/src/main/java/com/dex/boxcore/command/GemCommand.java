package com.dex.boxcore.command;

import com.dex.boxcore.config.Messages;
import com.dex.boxcore.gem.GemConfig;
import com.dex.boxcore.gem.GemFormat;
import com.dex.boxcore.gem.GemLedger;
import com.dex.boxcore.gem.GemTop;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** {@code /gem}, {@code /gem pay <player> <amount>}, {@code /gem top} (Update 03 §5.2, §5.3). */
public final class GemCommand implements TabExecutor {

    public static final String USE = "boxcore.gem.use";
    public static final String PAY = "boxcore.gem.pay";

    private static final String USAGE = "/gem [pay <player> <amount>|top]";
    private static final String USAGE_PAY = "/gem pay <player> <amount>";
    private static final String USAGE_CONSOLE = "/boxcore gem check <player>";
    private static final List<String> AMOUNTS = List.of("10", "100", "1000");

    private final Supplier<Messages> messages;
    private final GemLedger ledger;
    private final GemTop top;
    private final Map<UUID, Long> payCooldowns;
    private final ErrorReporter errors;

    public GemCommand(Supplier<Messages> messages, GemLedger ledger, GemTop top, Map<UUID, Long> payCooldowns,
                      ErrorReporter errors) {
        this.messages = messages;
        this.ledger = ledger;
        this.top = top;
        this.payCooldowns = payCooldowns;
        this.errors = errors;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            Messages msg = messages.get();
            if (!sender.hasPermission(USE)) {
                msg.send(sender, "command.no-permission");
                return true;
            }
            if (args.length == 0) {
                balance(sender);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "pay" -> pay(sender, args);
                case "top" -> {
                    if (args.length == 1) top(sender);
                    else msg.send(sender, "command.usage", "usage", USAGE);
                }
                default -> msg.send(sender, "command.usage", "usage", USAGE);
            }
        } catch (Throwable t) {
            errors.report("command /gem " + String.join(" ", args), t);
            try {
                messages.get().send(sender, "command.error");
            } catch (Throwable ignored) {
                // nothing escapes to Bukkit
            }
        }
        return true;
    }

    private void balance(CommandSender sender) {
        Messages msg = messages.get();
        if (!(sender instanceof Player player)) {
            msg.send(sender, "command.usage", "usage", USAGE_CONSOLE);
            return;
        }
        if (!ledger.enabled()) {
            msg.send(sender, "gem.disabled");
            return;
        }
        long balance = ledger.balance(player.getUniqueId());
        if (balance < 0) {
            msg.send(sender, "gem.not-loaded");
            return;
        }
        msg.send(sender, "gem.balance", "amount", GemFormat.grouped(balance));
    }

    /** Rules checked in the order of §5.2; the first failure sends its message and stops. */
    private void pay(CommandSender sender, String[] args) {
        Messages msg = messages.get();
        // 1. a player with the permission (checked now, so LuckPerms changes apply at once)
        if (!(sender instanceof Player player) || !sender.hasPermission(PAY)) {
            msg.send(sender, "command.no-permission");
            return;
        }
        if (args.length != 3) {
            msg.send(sender, "command.usage", "usage", USAGE_PAY);
            return;
        }
        if (!ledger.enabled()) {
            msg.send(sender, "gem.disabled");
            return;
        }
        // 2. sender loaded
        UUID from = player.getUniqueId();
        if (!ledger.isLoaded(from)) {
            msg.send(sender, "gem.not-loaded");
            return;
        }
        // 3. amount
        GemConfig cfg = ledger.config();
        long amount = GemFormat.parseAmount(args[2]);
        if (amount < 0 || amount > cfg.maxBalance()) {
            msg.send(sender, "gem.error.amount", "max", GemFormat.grouped(cfg.maxBalance()));
            return;
        }
        if (amount < cfg.payMinAmount()) {
            msg.send(sender, "gem.pay.min", "min", GemFormat.grouped(cfg.payMinAmount()));
            return;
        }
        // 4. target online, visible, not self, loaded
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !player.canSee(target)) {
            msg.send(sender, "error.player-offline", "player", args[1]);
            return;
        }
        if (target.getUniqueId().equals(from)) {
            msg.send(sender, "gem.pay.self");
            return;
        }
        if (!ledger.isLoaded(target.getUniqueId())) {
            msg.send(sender, "gem.not-loaded");
            return;
        }
        // 5. cooldown since the last successful pay
        long now = System.currentTimeMillis();
        Long last = payCooldowns.get(from);
        long cooldownMs = cfg.payCooldownSeconds() * 1000L;
        if (last != null && now - last < cooldownMs) {
            long seconds = (cooldownMs - (now - last) + 999) / 1000;
            msg.send(sender, "gem.pay.cooldown", "seconds", seconds);
            return;
        }
        // 6-7. balances, then both sides at once
        switch (ledger.pay(from, target.getUniqueId(), amount)) {
            case SUCCESS -> {
                payCooldowns.put(from, now);
                String text = GemFormat.grouped(amount);
                msg.send(sender, "gem.pay.sent", "amount", text, "player", target.getName());
                msg.notify(target, "gem.pay.received", "amount", text, "player", player.getName());
            }
            case INSUFFICIENT_FUNDS -> msg.send(sender, "gem.pay.insufficient",
                    "balance", GemFormat.grouped(ledger.balance(from)));
            case TARGET_FULL -> msg.send(sender, "gem.pay.target-full", "player", target.getName());
            case SENDER_NOT_LOADED, TARGET_NOT_LOADED -> msg.send(sender, "gem.not-loaded");
            case INVALID_AMOUNT -> msg.send(sender, "gem.error.amount", "max", GemFormat.grouped(cfg.maxBalance()));
        }
    }

    private void top(CommandSender sender) {
        Messages msg = messages.get();
        if (!ledger.enabled()) {
            msg.send(sender, "gem.disabled");
            return;
        }
        GemTop.Snapshot snapshot = top.snapshot();
        msg.sendLine(sender, msg.raw("gem.top.header"));
        if (snapshot.entries().isEmpty()) {
            msg.sendLine(sender, msg.raw("gem.top.empty"));
        } else {
            int rank = 1;
            for (GemTop.Entry entry : snapshot.entries()) {
                msg.sendLine(sender, msg.raw("gem.top.line"), "rank", rank++, "player", entry.name(),
                        "amount", GemFormat.grouped(entry.balance()));
            }
        }
        if (snapshot.builtAt() > 0) {
            long seconds = Math.max(0, (System.currentTimeMillis() - snapshot.builtAt()) / 1000);
            msg.sendLine(sender, msg.raw("gem.top.updated"), "seconds", seconds);
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        try {
            if (!sender.hasPermission(USE)) return List.of();
            if (args.length == 1) {
                List<String> subs = new ArrayList<>();
                if (sender.hasPermission(PAY)) subs.add("pay");
                subs.add("top");
                return filter(subs, args[0]);
            }
            if (!args[0].equalsIgnoreCase("pay") || !sender.hasPermission(PAY)) return List.of();
            if (args.length == 2) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.equals(sender)) continue;
                    if (sender instanceof Player self && !self.canSee(p)) continue;
                    names.add(p.getName());
                }
                return filter(names, args[1]);
            }
            if (args.length == 3) return filter(AMOUNTS, args[2]);
            return List.of();
        } catch (Throwable t) {
            errors.report("tab completion /gem", t);
            return List.of();
        }
    }

    static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }
}
