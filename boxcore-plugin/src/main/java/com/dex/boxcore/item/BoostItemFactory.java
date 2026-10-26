package com.dex.boxcore.item;

import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import com.dex.boxcore.config.Messages;
import com.dex.boxcore.util.TextWrap;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds boost bottles (§6.2). Name and lore are generated from the data and are display only. The material comes
 * from the type ({@code bottle-material}, Update 05 E1); a potion is a water bottle. Every bottle stacks to 64,
 * whatever its material (honey bottles stack to 16 and potions to 1 in vanilla).
 */
public final class BoostItemFactory {

    /** Stack size of every bottle, like the experience bottles of earlier versions. */
    public static final int MAX_STACK = 64;

    private final BottleKeys keys;

    public BoostItemFactory(BottleKeys keys) {
        this.keys = keys;
    }

    /** A personal bottle: {@link #create(BoostType, int, long, int, boolean, CoreConfig, Messages)}. */
    public ItemStack create(BoostType type, int tenths, long durationSeconds, int amount,
                            CoreConfig config, Messages messages) {
        return create(type, tenths, durationSeconds, amount, false, config, messages);
    }

    /**
     * Builds one bottle stack of {@code amount} items (clamped to {@link #MAX_STACK}).
     *
     * @param tenths the multiplier in tenths (Update 04 D1)
     * @param clan   a clan bottle (Update 05 E3.4): {@code boost_scope = clan}, the {@code clan-bottle-item} texts
     */
    public ItemStack create(BoostType type, int tenths, long durationSeconds, int amount, boolean clan,
                            CoreConfig config, Messages messages) {
        ItemStack item = new ItemStack(config.bottleMaterial(type));
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof PotionMeta potion) potion.setBasePotionType(PotionType.WATER);
        meta.setMaxStackSize(MAX_STACK);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keys.type(), PersistentDataType.STRING, type.id());
        if (clan) pdc.set(keys.scope(), PersistentDataType.STRING, BottleKeys.SCOPE_CLAN);
        if (tenths % Multipliers.ONE == 0) {
            pdc.set(keys.multiplier(), PersistentDataType.INTEGER, tenths / Multipliers.ONE); // stacks with old bottles
        } else {
            pdc.set(keys.multiplierTenths(), PersistentDataType.INTEGER, tenths);
        }
        pdc.set(keys.duration(), PersistentDataType.LONG, durationSeconds);

        String multiplier = messages.multiplier(tenths);
        String description = type.description(multiplier);
        Object[] placeholders = {
                "type", type.id(),
                "type_name", type.displayName(),
                "multiplier", multiplier,
                "duration", messages.duration(durationSeconds),
                "description", description,
                "click", messages.click(config.useClick())
        };
        String texts = clan ? "clan-bottle-item" : "bottle-item";
        meta.displayName(messages.renderItem(type, messages.raw(texts + ".name"), placeholders));
        List<String> loreTemplates = messages.rawList(texts + ".lore");
        List<Component> lore = new ArrayList<>(loreTemplates.size() + 2);
        for (String line : loreTemplates) {
            if (!line.contains("%description%")) {
                lore.add(messages.renderItem(type, line, placeholders));
                continue;
            }
            // one lore line per wrapped part of the description, each with the line's template
            for (String part : TextWrap.wrap(description, config.descriptionWidth())) {
                lore.add(messages.renderItem(type, line.replace("%description%", Messages.fill("%d%", "d", part)),
                        placeholders));
            }
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

        item.setItemMeta(meta);
        item.setAmount(Math.max(1, Math.min(amount, MAX_STACK)));
        return item;
    }
}
