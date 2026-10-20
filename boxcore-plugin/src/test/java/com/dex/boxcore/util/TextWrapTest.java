package com.dex.boxcore.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextWrapTest {

    @Test
    void wrapsBetweenWords() {
        assertEquals(List.of("Tăng x10 vật phẩm nhận", "được khi đào các khối"),
                TextWrap.wrap("Tăng x10 vật phẩm nhận được khi đào các khối", 25));
    }

    @Test
    void shortTextAndDisabledWidthStayOnOneLine() {
        assertEquals(List.of("Tăng x10"), TextWrap.wrap("Tăng x10", 25));
        assertEquals(List.of("a b c d e f"), TextWrap.wrap("a b c d e f", 0));
        assertEquals(List.of(""), TextWrap.wrap("  ", 10));
    }

    @Test
    void longWordGetsItsOwnLine() {
        assertEquals(List.of("ab", "abcdefghij", "cd"), TextWrap.wrap("ab abcdefghij cd", 5));
    }
}
