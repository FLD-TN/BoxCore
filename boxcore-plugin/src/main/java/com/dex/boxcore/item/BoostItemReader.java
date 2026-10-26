package com.dex.boxcore.item;

import com.dex.boxcore.api.Multipliers;
import com.dex.boxcore.config.BoostType;
import com.dex.boxcore.config.CoreConfig;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Reads boost bottles from the PersistentDataContainer only (§6.3, §11). The item's name and lore are
 * never read, so renaming a bottle changes nothing.
 */
public final class BoostItemReader {

    private final BottleKeys keys;

    public BoostItemReader(BottleKeys keys) {
        this.keys = keys;
    }

    /** True if the item carries {@code boost_type}, valid or not. Such items never get vanilla behaviour. */
    public boolean isBottle(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return false;
        return item.getPersistentDataContainer().has(keys.type());
    }

    /** Reads the raw data. @return the data, or {@code null} if a key is missing or has the wrong type */
    public BottleData read(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        try {
            PersistentDataContainerView pdc = item.getPersistentDataContainer();
            String type = pdc.get(keys.type(), PersistentDataType.STRING);
            Integer tenths = pdc.get(keys.multiplierTenths(), PersistentDataType.INTEGER);
            if (tenths == null) {
                Integer whole = pdc.get(keys.multiplier(), PersistentDataType.INTEGER);
                if (whole == null) return null;
                // out of range values stay invalid without overflowing
                tenths = whole > BoostType.LIMIT_TENTHS / Multipliers.ONE || whole < 0 ? -1 : whole * Multipliers.ONE;
            }
            Long duration = pdc.get(keys.duration(), PersistentDataType.LONG);
            if (type == null || duration == null) return null;
            boolean clan = BottleKeys.SCOPE_CLAN.equals(pdc.get(keys.scope(), PersistentDataType.STRING));
            return new BottleData(type, tenths, duration, clan);
        } catch (IllegalArgumentException wrongType) {
            return null;
        }
    }

    /**
     * Re-validates bottle data against the CURRENT config: type declared, multiplier in
     * {@code [min, max-multiplier]} and whole unless the type is decimal, duration in {@code (0, max-duration]}.
     *
     * @return the type if valid, otherwise {@code null}
     */
    public static BoostType validate(BottleData data, CoreConfig config) {
        if (data == null) return null;
        BoostType type = config.type(data.type());
        if (type == null) return null;
        if (!type.isValidMultiplier(data.tenths())) return null;
        if (!type.isValidDuration(data.durationSeconds())) return null;
        return type;
    }
}
