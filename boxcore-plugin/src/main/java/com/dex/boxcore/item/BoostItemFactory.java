package com.dex.boxcore.item;

import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Builds boost bottles (§6.2). Name and lore are generated from the data and are display only. */
public final class BoostItemFactory {

    private final BottleKeys keys;

    public BoostItemFactory(BottleKeys keys) {
        this.keys = keys;
    }

    /** Builds one bottle stack of {@code amount} items (clamped to the material's max stack size). */
    public ItemStack create(BoostType type, int multiplier, long durationSeconds, int amount,
                            CoreConfig config, Messages messages) {
        ItemStack item = new ItemStack(config.bottleMaterial());
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.type(), PersistentDataType.STRING, type.id());
        pdc.set(keys.multiplier(), PersistentDataType.INTEGER, multiplier);
        pdc.set(keys.duration(), PersistentDataType.LONG, durationSeconds);

        Object[] placeholders = {
                "type", type.id(),
                "type_name", type.displayName(),
                "multiplier", multiplier,
                "duration", messages.duration(durationSeconds),
                "description", type.description(multiplier),
                "click", messages.click(config.useClick())
        };
        meta.displayName(messages.renderItem(type, messages.raw("bottle-item.name"), placeholders));
        List<String> loreTemplates = messages.rawList("bottle-item.lore");
        List<Component> lore = new ArrayList<>(loreTemplates.size());
        for (String line : loreTemplates) lore.add(messages.renderItem(type, line, placeholders));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

        item.setItemMeta(meta);
        return item;
    }
}
