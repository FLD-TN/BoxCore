package com.dex.boxcore.command;

import com.dex.boxcore.boost.BoostController;
import com.dex.boxcore.boost.EffectiveCache;
import com.dex.boxcore.boost.GlobalBoost;
import com.dex.boxcore.boost.MultiplierCalculator;
import com.dex.boxcore.boost.PersonalBoost;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.item.BoostItemFactory;
import com.dex.boxcore.util.DurationFormat;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** {@code /boxcore give|global|reload|check} (§10). Every sub-command works from the console. */
public final class BoxCoreCommand implements TabExecutor {

    public static final String ADMIN = "boxcore.admin";

    private static final String USAGE_ROOT = "/boxcore <give|global|reload|check>";
    private static final String USAGE_GIVE = "/boxcore give <player> <type> <multiplier> <duration> [amount]";
    private static final String USAGE_GLOBAL = "/boxcore global <start|stop> <type> [multiplier] [duration]";
    private static final String USAGE_GLOBAL_START = "/boxcore global start <type> <multiplier> <duration>";
    private static final String USAGE_GLOBAL_STOP = "/boxcore global stop <type>";
    private static final String USAGE_RELOAD = "/boxcore reload";
    private static final String USAGE_CHECK = "/boxcore check <player>";

    private static final List<String> SUBCOMMANDS = List.of("give", "global", "reload", "check");
    private static final List<String> MULTIPLIERS = List.of("2", "3", "5", "10");
    private static final List<String> DURATIONS = List.of("10m", "30m", "1h");
    private static final List<String> AMOUNTS = List.of("1", "16", "32", "64");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm dd/MM");

    private final Supplier<CoreConfig> config;
    private final Supplier<Messages> messages;
    private final BoostController controller;
    private final BoostItemFactory factory;
    private final Consumer<CommandSender> reload;
    private final ErrorReporter errors;

    public BoxCoreCommand(Supplier<CoreConfig> config, Supplier<Messages> messages, BoostController controller,
                          BoostItemFactory factory, Consumer<CommandSender> reload, ErrorReporter errors) {
        this.config = config;
        this.messages = messages;
        this.controller = controller;
        this.factory = factory;
        this.reload = reload;
        this.errors = errors;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (!sender.hasPermission(ADMIN)) {
                messages.get().send(sender, "command.no-permission");
                return true;
            }
            if (args.length == 0) {
                usage(sender, USAGE_ROOT);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> give(sender, args);
                case "global" -> global(sender, args);
                case "reload" -> {
                    if (args.length != 1) usage(sender, USAGE_RELOAD);
                    else reload.accept(sender);
                }
                case "check" -> check(sender, args);
                default -> usage(sender, USAGE_ROOT);
            }
        } catch (Throwable t) {
            errors.report("command /boxcore " + String.join(" ", args), t);
            try {
                messages.get().send(sender, "command.error");
            } catch (Throwable ignored) {
                // nothing escapes to Bukkit
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ give

    private void give(CommandSender sender, String[] args) {
        Messages msg = messages.get();
        if (args.length < 5 || args.length > 6) {
            usage(sender, USAGE_GIVE);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg.send(sender, "error.player-offline", "player", args[1]);
            return;
        }
        BoostType type = type(sender, args[2]);
        if (type == null) return;
        Integer multiplier = multiplier(sender, type, args[3]);
        if (multiplier == null) return;
        long duration = duration(sender, type, args[4]);
        if (duration <= 0) return;
        int amount = 1;
        if (args.length == 6) {
            long parsed = parseInt(args[5]);
            if (parsed < 1 || parsed > 64) {
                msg.send(sender, "error.amount");
                return;
            }
            amount = (int) parsed;
        }

        CoreConfig cfg = config.get();
        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = factory.create(type, multiplier, duration, remaining, cfg, msg);
            remaining -= stack.getAmount();
            for (ItemStack leftover : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
        }

        String durationText = msg.duration(duration);
        msg.send(sender, type, "give.done", "amount", amount, "type", type.id(), "type_name", type.displayName(),
                "multiplier", multiplier, "duration", durationText, "player", target.getName());
        msg.send(target, type, "give.received", "amount", amount, "type", type.id(), "type_name", type.displayName(),
                "multiplier", multiplier, "duration", durationText);
    }

    // ------------------------------------------------------------------ global

    private void global(CommandSender sender, String[] args) {
        Messages msg = messages.get();
        if (args.length < 2) {
            usage(sender, USAGE_GLOBAL);
            return;
        }
        long now = System.currentTimeMillis();
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                if (args.length != 5) {
                    usage(sender, USAGE_GLOBAL_START);
                    return;
                }
                BoostType type = type(sender, args[2]);
                if (type == null) return;
                Integer multiplier = multiplier(sender, type, args[3]);
                if (multiplier == null) return;
                long duration = duration(sender, type, args[4]);
                if (duration <= 0) return;
                GlobalBoost replaced = controller.startGlobal(type, multiplier, duration, now);
                if (replaced != null) {
                    msg.send(sender, type, "global.replaced", "type", type.id(), "type_name", type.displayName(),
                            "old", replaced.multiplier(), "multiplier", multiplier);
                }
                msg.broadcast(type, "global.started", "type", type.id(), "type_name", type.displayName(),
                        "multiplier", multiplier, "duration", msg.duration(duration));
                msg.broadcast(type, "global.no-stack-note", "type", type.id(), "type_name", type.displayName(),
                        "multiplier", multiplier);
            }
            case "stop" -> {
                if (args.length != 3) {
                    usage(sender, USAGE_GLOBAL_STOP);
                    return;
                }
                String id = args[2].toLowerCase(Locale.ROOT);
                BoostType type = config.get().type(id);
                if (type == null && controller.registry().global(id) == null) {
                    msg.send(sender, "error.unknown-type", "type", args[2]);
                    return;
                }
                GlobalBoost stopped = controller.stopGlobal(id, now);
                if (stopped == null) {
                    msg.send(sender, "global.not-active", "type", id);
                    return;
                }
                msg.broadcast(type, "global.stopped", "type", id, "type_name", type == null ? id : type.displayName());
            }
            default -> usage(sender, USAGE_GLOBAL);
        }
    }

    // ------------------------------------------------------------------ check

    private void check(CommandSender sender, String[] args) {
        Messages msg = messages.get();
        if (args.length != 2) {
            usage(sender, USAGE_CHECK);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            msg.send(sender, "error.player-offline", "player", args[1]);
            return;
        }
        CoreConfig cfg = config.get();
        long now = System.currentTimeMillis();
        EffectiveCache.Snapshot snapshot = controller.refresh(target, now);
        DateTimeFormatter clock = CLOCK.withZone(cfg.timezone());

        msg.sendLine(sender, msg.raw("check.header"), "player", target.getName());
        List<PersonalBoost> personal = controller.registry().personal(target.getUniqueId());
        for (BoostType type : cfg.typeList()) {
            String id = type.id();
            int rank = snapshot.rank(id);
            int bottle = snapshot.bottle(id);
            int global = snapshot.global(id);
            msg.sendLine(sender, type, msg.raw("check.type"), "type", id, "type_name", type.displayName(),
                    "effective", snapshot.effective(id), "max", type.maxMultiplier());
            msg.sendLine(sender, type, msg.raw("check.sum"), "rank", rank, "bottle", bottle,
                    "personal", snapshot.personal(id));
            GlobalBoost g = controller.registry().global(id);
            if (global == 0 || g == null) {
                msg.sendLine(sender, type, msg.raw("check.global-none"));
            } else {
                String key = MultiplierCalculator.globalWins(rank, bottle, global) ? "check.global-used" : "check.global";
                msg.sendLine(sender, type, msg.raw(key), "multiplier", global,
                        "remaining", DurationFormat.clock(DurationFormat.remainingSeconds(g.expiresAt(), now)),
                        "time", clock.format(Instant.ofEpochMilli(g.expiresAt())));
            }
            PersonalBoost active = MultiplierCalculator.displayed(personal, id, now);
            if (active == null) {
                msg.sendLine(sender, type, msg.raw("check.bottle-none"));
            } else {
                msg.sendLine(sender, type, msg.raw("check.bottle"), "multiplier", active.multiplier(),
                        "remaining", DurationFormat.clock(DurationFormat.remainingSeconds(active.expiresAt(), now)),
                        "time", clock.format(Instant.ofEpochMilli(active.expiresAt())));
            }
            if (MultiplierCalculator.personal(rank, bottle) > type.maxMultiplier() || global > type.maxMultiplier()) {
                msg.sendLine(sender, type, msg.raw("check.capped"), "max", type.maxMultiplier());
            }
        }
    }

    // ------------------------------------------------------------------ argument parsing

    private BoostType type(CommandSender sender, String raw) {
        BoostType type = config.get().type(raw.toLowerCase(Locale.ROOT));
        if (type == null) messages.get().send(sender, "error.unknown-type", "type", raw);
        return type;
    }

    private Integer multiplier(CommandSender sender, BoostType type, String raw) {
        long value = parseInt(raw);
        if (!type.isValidMultiplier(value)) {
            messages.get().send(sender, "error.multiplier", "max", type.maxMultiplier());
            return null;
        }
        return (int) value;
    }

    private long duration(CommandSender sender, BoostType type, String raw) {
        long seconds = DurationFormat.parse(raw);
        if (!type.isValidDuration(seconds)) {
            Messages msg = messages.get();
            msg.send(sender, "error.duration", "max", msg.duration(type.maxDurationSeconds()));
            return -1;
        }
        return seconds;
    }

    /** Strict non-negative integer (digits only, at most 9); -1 if invalid. */
    private static long parseInt(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 9) return -1;
        long value = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value;
    }

    private void usage(CommandSender sender, String usage) {
        messages.get().send(sender, "command.usage", "usage", usage);
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        try {
            if (!sender.hasPermission(ADMIN)) return List.of();
            return complete(args);
        } catch (Throwable t) {
            errors.report("tab completion", t);
            return List.of();
        }
    }

    private List<String> complete(String[] args) {
        if (args.length == 1) return filter(SUBCOMMANDS, args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> types = new ArrayList<>(config.get().types().keySet());
        return switch (sub) {
            case "give" -> switch (args.length) {
                case 2 -> filter(players(), args[1]);
                case 3 -> filter(types, args[2]);
                case 4 -> filter(MULTIPLIERS, args[3]);
                case 5 -> filter(DURATIONS, args[4]);
                case 6 -> filter(AMOUNTS, args[5]);
                default -> List.of();
            };
            case "global" -> {
                if (args.length == 2) yield filter(List.of("start", "stop"), args[1]);
                String action = args[1].toLowerCase(Locale.ROOT);
                if (action.equals("start")) {
                    yield switch (args.length) {
                        case 3 -> filter(types, args[2]);
                        case 4 -> filter(MULTIPLIERS, args[3]);
                        case 5 -> filter(DURATIONS, args[4]);
                        default -> List.of();
                    };
                }
                if (action.equals("stop") && args.length == 3) {
                    yield filter(new ArrayList<>(controller.registry().globals().keySet()), args[2]);
                }
                yield List.of();
            }
            case "check" -> args.length == 2 ? filter(players(), args[1]) : List.of();
            default -> List.of();
        };
    }

    private static List<String> players() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }
}
