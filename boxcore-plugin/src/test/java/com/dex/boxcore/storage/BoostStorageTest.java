package com.dex.boxcore.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Update 04 D6: the {@code multiplier} key of the boost files, in tenths. */
class BoostStorageTest {

    private static Number yamlNumber(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("multiplier: " + text);
        return (Number) yaml.get("multiplier");
    }

    @Test
    void wholeAndDecimalValues() throws Exception {
        assertEquals(30, BoostStorage.tenths(yamlNumber("3")));
        assertEquals(15, BoostStorage.tenths(yamlNumber("1.5")));
        assertEquals(20, BoostStorage.tenths(yamlNumber("2.0")));
        assertEquals(1005, BoostStorage.tenths(yamlNumber("100.5")));
        assertEquals(30, BoostStorage.tenths(3L));
    }

    @Test
    void invalidValues() throws Exception {
        assertEquals(-1, BoostStorage.tenths(yamlNumber("1.25")));
        assertEquals(-1, BoostStorage.tenths(yamlNumber("-2")));
        assertEquals(-1, BoostStorage.tenths(yamlNumber("20000")));
        assertEquals(-1, BoostStorage.tenths(yamlNumber(".nan")));
        assertEquals(-1, BoostStorage.tenths(Double.POSITIVE_INFINITY));
    }
}
