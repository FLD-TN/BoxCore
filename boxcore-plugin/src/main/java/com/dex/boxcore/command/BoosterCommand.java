package com.dex.boxcore.command;

import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.util.ErrorReporter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.function.Supplier;

/** {@code /booster}: the usage guide in chat (§10.1). */
public final class BoosterCommand implements TabExecutor {
    
    public static final String USE = "boxcore.use";

    private final Supplier<CoreConfig> config;
    private final Supplier<Messages> messages;
    private final ErrorReporter errors;

    public BoosterCommand(Supplier<CoreConfig> config, Supplier<Messages> messages, ErrorReporter errors) {
        this.config = config;
        this.messages = messages;
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
            String click = msg.click(config.get().useClick());
            for (String line : msg.rawList("booster.guide")) {
                msg.sendLine(sender, line, "click", click);
            }
        } catch (Throwable t) {
            errors.report("command /booster", t);
            try {
                messages.get().send(sender, "command.error");
            } catch (Throwable ignored) {
                // nothing escapes to Bukkit
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
