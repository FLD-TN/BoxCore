package com.dex.boxcore.command;

import com.dex.boxcore.api.GemResult;
import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.boost.BoostController;
import com.dex.boxcore.boost.ClanBoost;
import com.dex.boxcore.boost.EffectiveCache;
import com.dex.boxcore.boost.GlobalBoost;
import com.dex.boxcore.boost.MultiplierCalculator;
import com.dex.boxcore.boost.PersonalBoost;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.gem.GemConfig;
import com.dex.boxcore.gem.GemDatabase;
import com.dex.boxcore.gem.GemFormat;
import com.dex.boxcore.gem.GemLedger;
import com.dex.boxcore.item.BoostItemFactory;
import com.dex.boxcore.util.DurationFormat;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** {@code /boxcore give|global|gem|reload|check} (§10, Update 03 §5.1). Every sub-command works from the console. */
public final class BoxCoreCommand implements TabExecutor {

    public static final String ADMIN = "boxcore.admin";

    private static final String USAGE_ROOT = "/boxcore <give|global|gem|reload|check>";
    private static final String USAGE_GIVE = "/boxcore give <player> <type> <multiplier> <duration> [amount] [clan]";
    /** Last argument of {@code give} for a clan bottle (Update 05 E3.4). */
    private static final String CLAN = "clan";
    private static final String USAGE_GLOBAL = "/boxcore global <start|stop> <type> [multiplier] [duration]";
    private static final String USAGE_GLOBAL_START = "/boxcore global start <type> <multiplier> <duration>";
    private static final String USAGE_GLOBAL_STOP = "/boxcore global stop <type>";
    private static final String USAGE_RELOAD = "/boxcore reload";
    private static final String USAGE_CHECK = "/boxcore check <player>";
    private static final String USAGE_GEM = "/boxcore gem <give|take|set|check> <player> [amount] [reason] [-s]";
    private static final String USAGE_GEM_CHECK = "/boxcore gem check <player>";
    private static final String SILENT = "-s";
    private static final int CHECK_ROWS = 5;

    private static final List<String> SUBCOMMANDS = List.of("give", "global", "gem", "reload", "check");
    private static final List<String> GEM_ACTIONS = List.of("give", "take", "set", "check");
    private static final List<String> GEM_AMOUNTS = List.of("10", "100", "1000");
    private static final List<String> MULTIPLIERS = List.of("2", "3", "5", "10");
    /** Suggestions for a type with {@code decimal: true} (Update 04 D7). */
    private static final List<String> DECIMAL_MULTIPLIERS = List.of("1.5", "2", "2.5", "3", "5", "10");
    private static final List<String> DURATIONS = List.of("10m", "30m", "1h");
    private static final List<String> AMOUNTS = List.of("1", "16", "32", "64");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm dd/MM");

    private final Supplier<CoreConfig> config;
    private final Supplier<Messages> messages;
    private final BoostController controller;
    private final BoostItemFactory factory;
    private final Consumer<CommandSender> reload;
    private final GemLedger gems;
    private final ErrorReporter errors;

    public BoxCoreCommand(Supplier<CoreConfig> config, Supplier<Messages> messages, BoostController controller,
                          BoostItemFactory factory, Consumer<CommandSender> reload, GemLedger gems,
                          ErrorReporter errors) {
        this.config = config;
        this.messages = messages;
        this.controller = controller;
        this.factory = factory;
        this.reload = reload;
        this.gems = gems;
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
                case "gem" -> gem(sender, args);
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
        boolean clan = args.length >= 6 && args[args.length - 1].equalsIgnoreCase(CLAN);
        if (clan) args = Arrays.copyOf(args, args.length - 1);
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
        String multiplierText = msg.multiplier(multiplier);
        int remaining = amount;
        while (remaining > 0) {
            ItemStack stack = factory.create(type, multiplier, duration, remaining, clan, cfg, msg);
            remaining -= stack.getAmount();
            for (ItemStack leftover : target.getInventory().addItem(stack).values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
        }

        String durationText = msg.duration(duration);
        String done = clan ? "give.done-clan" : "give.done";
        String received = clan ? "give.received-clan" : "give.received";
        msg.send(sender, type, done, "amount", amount, "type", type.id(), "type_name", type.displayName(),
                "multiplier", multiplierText, "duration", durationText, "player", target.getName());
        msg.notify(target, type, received, "amount", amount, "type", type.id(), "type_name", type.displayName(),
                "multiplier", multiplierText, "duration", durationText);
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
                String multiplierText = msg.multiplier(multiplier);
                if (replaced != null) {
                    msg.send(sender, type, "global.replaced", "type", type.id(), "type_name", type.displayName(),
                            "old", msg.multiplier(replaced.tenths()), "multiplier", multiplierText);
                }
                msg.broadcast(type, "global.started", "type", type.id(), "type_name", type.displayName(),
                        "multiplier", multiplierText, "duration", msg.duration(duration));
                msg.broadcast(type, "global.no-stack-note", "type", type.id(), "type_name", type.displayName(),
                        "multiplier", multiplierText);
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
                    "effective", msg.multiplier(snapshot.effective(id)), "max", msg.multiplier(type.maxTenths()));
            msg.sendLine(sender, type, msg.raw("check.sum"), "rank", msg.multiplier(rank),
                    "bottle", msg.multiplier(bottle), "personal", msg.multiplier(snapshot.personal(id)));
            GlobalBoost g = controller.registry().global(id);
            if (global == 0 || g == null) {
                msg.sendLine(sender, type, msg.raw("check.global-none"));
            } else {
                String key = MultiplierCalculator.globalWins(rank, bottle, global) ? "check.global-used" : "check.global";
                msg.sendLine(sender, type, msg.raw(key), "multiplier", msg.multiplier(global),
                        "remaining", DurationFormat.clock(DurationFormat.remainingSeconds(g.expiresAt(), now)),
                        "time", clock.format(Instant.ofEpochMilli(g.expiresAt())));
            }
            PersonalBoost active = MultiplierCalculator.displayed(personal, id, now);
            if (active == null) {
                msg.sendLine(sender, type, msg.raw("check.bottle-none"));
            } else {
                msg.sendLine(sender, type, msg.raw("check.bottle"), "multiplier", msg.multiplier(active.tenths()),
                        "remaining", DurationFormat.clock(DurationFormat.remainingSeconds(active.expiresAt(), now)),
                        "time", clock.format(Instant.ofEpochMilli(active.expiresAt())));
            }
            int clanTenths = snapshot.clan(id);
            ClanBoost clanBoost = controller.clanBoost(snapshot.clan(), id, now);
            if (clanTenths == 0 || clanBoost == null) {
                msg.sendLine(sender, type, msg.raw("check.clan-none"));
            } else {
                String key = clanTenths > Math.max(MultiplierCalculator.personal(rank, bottle), global)
                        ? "check.clan-used" : "check.clan";
                msg.sendLine(sender, type, msg.raw(key), "multiplier", msg.multiplier(clanTenths),
                        "remaining", DurationFormat.clock(DurationFormat.remainingSeconds(clanBoost.expiresAt(), now)),
                        "time", clock.format(Instant.ofEpochMilli(clanBoost.expiresAt())));
            }
            if (MultiplierCalculator.personal(rank, bottle) > type.maxTenths() || global > type.maxTenths()
                    || clanTenths > type.maxTenths()) {
                msg.sendLine(sender, type, msg.raw("check.capped"), "max", msg.multiplier(type.maxTenths()));
            }
        }
    }

    // ------------------------------------------------------------------ gem (Update 03 §5.1)

    private void gem(CommandSender sender, String[] args) {
        Messages msg = messages.get();
        if (args.length < 3) {
            usage(sender, USAGE_GEM);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!GEM_ACTIONS.contains(action)) {
            usage(sender, USAGE_GEM);
            return;
        }
        if (!gems.enabled()) {
            msg.send(sender, "gem.disabled");
            return;
        }
        if (action.equals("check")) {
            if (args.length != 3) usage(sender, USAGE_GEM_CHECK);
            else gemCheck(sender, args[2]);
            return;
        }
        if (args.length < 4) {
            usage(sender, USAGE_GEM);
            return;
        }
        GemLedger.Action kind = switch (action) {
            case "give" -> GemLedger.Action.GIVE;
            case "take" -> GemLedger.Action.TAKE;
            default -> GemLedger.Action.SET;
        };
        GemConfig cfg = gems.config();
        long amount = GemFormat.parseAmount(args[3]);
        long min = kind == GemLedger.Action.SET ? 0 : 1;
        if (amount < min || amount > cfg.maxBalance()) {
            msg.send(sender, "gem.error.amount", "max", GemFormat.grouped(cfg.maxBalance()));
            return;
        }
        boolean silent = false;
        String reason = null;
        for (int i = 4; i < args.length; i++) {
            if (args[i].equalsIgnoreCase(SILENT)) {
                silent = true;
            } else if (reason == null) {
                reason = args[i];
            } else {
                usage(sender, USAGE_GEM);
                return;
            }
        }
        if (reason == null) {
            String by = sender instanceof Player p ? p.getName() : "console";
            reason = "admin:" + by + ":" + action;
        }

        Player online = Bukkit.getPlayerExact(args[2]);
        if (online != null && gems.isLoaded(online.getUniqueId())) {
            GemLedger.Change change = gems.change(online.getUniqueId(), kind, amount, reason);
            gemReply(sender, kind, online.getName(), online.getUniqueId(), amount, change, silent);
            return;
        }
        // offline, or online but not loaded yet: through the database (§3.4)
        UUID id;
        String name;
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
        } else {
            OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(args[2]);
            if (known == null) {
                msg.send(sender, "gem.error.unknown-player", "player", args[2]);
                return;
            }
            id = known.getUniqueId();
            name = known.getName() != null ? known.getName() : args[2];
        }
        boolean quiet = silent;
        gems.changeStored(id, name, kind, amount, reason)
                .thenAccept(change -> gemReply(sender, kind, name, id, amount, change, quiet));
    }

    /** Replies to the admin and, unless silent, notifies the target if online. Main thread. */
    private void gemReply(CommandSender sender, GemLedger.Action kind, String name, UUID id, long amount,
                          GemLedger.Change change, boolean silent) {
        try {
            Messages msg = messages.get();
            String text = GemFormat.grouped(amount);
            if (change.result() != GemResult.SUCCESS) {
                switch (change.result()) {
                    case INSUFFICIENT_FUNDS -> msg.send(sender, "gem.admin.insufficient", "player", name,
                            "balance", GemFormat.grouped(change.balance()));
                    case BALANCE_LIMIT -> msg.send(sender, "gem.admin.limit", "player", name,
                            "max", GemFormat.grouped(gems.config().maxBalance()));
                    case INVALID_AMOUNT -> msg.send(sender, "gem.error.amount",
                            "max", GemFormat.grouped(gems.config().maxBalance()));
                    default -> msg.send(sender, gems.enabled() ? "command.error" : "gem.disabled");
                }
                return;
            }
            String balance = GemFormat.grouped(change.balance());
            Player target = Bukkit.getPlayer(id);
            switch (kind) {
                case GIVE -> {
                    msg.send(sender, "gem.admin.given", "amount", text, "player", name, "balance", balance);
                    if (!silent && target != null) msg.notify(target, "gem.received", "amount", text);
                }
                case TAKE -> {
                    msg.send(sender, "gem.admin.taken", "amount", text, "player", name, "balance", balance);
                    if (!silent && target != null) msg.notify(target, "gem.taken", "amount", text);
                }
                case SET -> {
                    msg.send(sender, "gem.admin.set", "amount", text, "player", name);
                    if (!silent && target != null && change.delta() != 0) msg.notify(target, "gem.set", "amount", text);
                }
            }
        } catch (Throwable t) {
            errors.report("gem command reply", t);
        }
    }

    private void gemCheck(CommandSender sender, String rawName) {
        Messages msg = messages.get();
        Player online = Bukkit.getPlayerExact(rawName);
        UUID id;
        String name;
        if (online != null) {
            id = online.getUniqueId();
            name = online.getName();
        } else {
            OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(rawName);
            if (known == null) {
                msg.send(sender, "gem.error.unknown-player", "player", rawName);
                return;
            }
            id = known.getUniqueId();
            name = known.getName() != null ? known.getName() : rawName;
        }
        gems.check(id, CHECK_ROWS).whenComplete((check, error) -> {
            try {
                Messages m = messages.get();
                if (error != null) {
                    errors.report("gem check", error);
                    m.send(sender, "command.error");
                    return;
                }
                long balance = check.balance() == null ? 0 : check.balance();
                m.sendLine(sender, m.raw("gem.admin.check-header"), "player", name,
                        "balance", GemFormat.grouped(balance));
                if (check.recent().isEmpty()) {
                    m.sendLine(sender, m.raw("gem.admin.check-empty"));
                    return;
                }
                DateTimeFormatter clock = CLOCK.withZone(config.get().timezone());
                for (GemDatabase.TxRow row : check.recent()) {
                    m.sendLine(sender, m.raw("gem.admin.check-line"),
                            "time", clock.format(Instant.ofEpochMilli(row.at())),
                            "delta", GemFormat.signed(row.delta()),
                            "balance", GemFormat.grouped(row.balanceAfter()),
                            "reason", row.reason());
                }
            } catch (Throwable t) {
                errors.report("gem check reply", t);
            }
        });
    }

    // ------------------------------------------------------------------ argument parsing

    private BoostType type(CommandSender sender, String raw) {
        BoostType type = config.get().type(raw.toLowerCase(Locale.ROOT));
        if (type == null) messages.get().send(sender, "error.unknown-type", "type", raw);
        return type;
    }

    /** {@code 2}, {@code 1.5} or {@code 1,5} in tenths (Update 04 D7), {@code null} with an error if invalid. */
    private Integer multiplier(CommandSender sender, BoostType type, String raw) {
        int tenths = Multipliers.parse(raw);
        if (!type.isValidMultiplier(tenths)) {
            Messages msg = messages.get();
            msg.send(sender, type.decimal() ? "error.multiplier-decimal" : "error.multiplier",
                    "max", msg.multiplier(type.maxTenths()));
            return null;
        }
        return tenths;
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
                case 4 -> filter(multipliers(args[2]), args[3]);
                case 5 -> filter(DURATIONS, args[4]);
                case 6 -> {
                    List<String> options = new ArrayList<>(AMOUNTS);
                    options.add(CLAN);
                    yield filter(options, args[5]);
                }
                case 7 -> args[5].equalsIgnoreCase(CLAN) ? List.of() : filter(List.of(CLAN), args[6]);
                default -> List.of();
            };
            case "global" -> {
                if (args.length == 2) yield filter(List.of("start", "stop"), args[1]);
                String action = args[1].toLowerCase(Locale.ROOT);
                if (action.equals("start")) {
                    yield switch (args.length) {
                        case 3 -> filter(types, args[2]);
                        case 4 -> filter(multipliers(args[2]), args[3]);
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
            case "gem" -> {
                boolean check = args[1].equalsIgnoreCase("check");
                yield switch (args.length) {
                    case 2 -> filter(GEM_ACTIONS, args[1]);
                    case 3 -> filter(players(), args[2]);
                    case 4 -> check ? List.of() : filter(GEM_AMOUNTS, args[3]);
                    default -> check ? List.of() : filter(List.of(SILENT), args[args.length - 1]);
                };
            }
            default -> List.of();
        };
    }

    private List<String> multipliers(String rawType) {
        BoostType type = config.get().type(rawType.toLowerCase(Locale.ROOT));
        return type != null && type.decimal() ? DECIMAL_MULTIPLIERS : MULTIPLIERS;
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
