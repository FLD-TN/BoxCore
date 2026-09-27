package com.dex.boxcoretest;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal consumer used to verify the BoxCore integration contract:
 * runs with BoxCore (sees boosts) and without it (multiplier 1, no errors).
 * The constants "mining" / "quest" are written as literals on purpose: consumers
 * must not touch com.dex.boxcore.api outside the hook class.
 */
public final class BoxCoreTestPlugin extends JavaPlugin {

    private boolean hooked;

    @Override
    public void onEnable() {
        // guard class loading: BoxCoreHook (and the API) is only touched when BoxCore is enabled
        if (Bukkit.getPluginManager().isPluginEnabled("BoxCore")) {
            try {
                BoxCoreHook.init();
                hooked = BoxCoreHook.active();
            } catch (NoClassDefFoundError e) {
                hooked = false;
            }
        }
        getLogger().info(hooked ? "BoxCore hook active" : "BoxCore not present, multipliers are 1");
    }

    private int multiplier(UUID player, String type) {
        return hooked ? BoxCoreHook.multiplier(player, type) : 1;
    }

    private long multiply(UUID player, String type, long amount) {
        return hooked ? BoxCoreHook.multiply(player, type, amount) : amount;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        UUID id;
        String name;
        if (args.length >= 1) {
            OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
            if (target == null) target = Bukkit.getOfflinePlayer(args[0]);
            id = target.getUniqueId();
            name = args[0];
        } else if (sender instanceof Player p) {
            id = p.getUniqueId();
            name = p.getName();
        } else {
            id = UUID.randomUUID();
            name = "random-offline-uuid";
        }
        sender.sendMessage("[BoxCoreTest] hooked=" + hooked + " player=" + name);
        sender.sendMessage("  mining x" + multiplier(id, "mining") + ", quest x" + multiplier(id, "quest")
                + ", unknown x" + multiplier(id, "fishing"));
        sender.sendMessage("  multiply(mining, 64) = " + multiply(id, "mining", 64)
                + ", multiply(quest, 1000) = " + multiply(id, "quest", 1000));
        if (hooked) {
            sender.sendMessage("  null args -> " + BoxCoreHook.multiplier(null, null)
                    + ", saturation -> " + (BoxCoreHook.multiply(id, "mining", Long.MAX_VALUE / 2) > 0));
        }
        // async read must be safe
        final UUID target = id;
        CompletableFuture.supplyAsync(() -> multiplier(target, "mining"))
                .whenComplete((m, err) -> Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage("  async mining x" + m + (err == null ? "" : " ERROR " + err))));
        return true;
    }
}
