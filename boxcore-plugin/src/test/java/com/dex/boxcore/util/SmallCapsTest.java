package com.dex.boxcore.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SmallCapsTest {

    @Test
    void convertsAsciiLettersOnly() {
        assertEquals("ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ", SmallCaps.convert("abcdefghijklmnopqrstuvwxyz"));
        assertEquals("ʙᴏᴏꜱᴛ ᴍɪɴɪɴɢ", SmallCaps.convert("Boost MINING"));
        assertEquals("123 ,.()", SmallCaps.convert("123 ,.()"));
    }

    @Test
    void keepsDiacritics() {
        // letters with diacritics are unchanged, plain letters are converted
        assertEquals("ᴛăɴɢ ᴠậᴛ ᴘʜẩᴍ", SmallCaps.convert("Tăng vật phẩm"));
        assertEquals("Đã ᴋíᴄʜ ʜᴏạᴛ", SmallCaps.convert("Đã kích hoạt"));
    }

    @Test
    void keepsTagsAndPlaceholders() {
        assertEquals("<accent>ʙᴏᴏꜱᴛ %type_name% x%multiplier% <muted>• ᴄòɴ <text>%remaining%",
                SmallCaps.convert("<accent>Boost %type_name% x%multiplier% <muted>• còn <text>%remaining%"));
        assertEquals("</text><#FFFFFF>ᴀ", SmallCaps.convert("</text><#FFFFFF>a"));
        assertEquals("(%type%)", SmallCaps.convert("(%type%)"));
    }

    @Test
    void loneSymbolsAreNotTagsOrPlaceholders() {
        assertEquals("ᴀ < ʙ", SmallCaps.convert("a < b"));
        assertEquals("100% ꜱᴜʀᴇ", SmallCaps.convert("100% sure"));
        assertEquals("x10 ᴀɴᴅ 50%", SmallCaps.convert("x10 and 50%"));
    }

    @Test
    void emptyAndNull() {
        assertEquals("", SmallCaps.convert(""));
        assertNull(SmallCaps.convert((String) null));
    }
}
