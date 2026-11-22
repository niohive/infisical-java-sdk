package com.niohive.infisical.env.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StringsTest {

    @Test
    void blankMeansNullEmptyOrOnlyWhitespaceLikeJava11IsBlank() {
        assertTrue(Strings.isBlank(null));
        assertTrue(Strings.isBlank(""));
        assertTrue(Strings.isBlank(" \t\n\r"));
        assertTrue(Strings.isBlank(" "), "em space is whitespace");
        assertFalse(Strings.isBlank(" "), "no-break space is not whitespace");
        assertFalse(Strings.isBlank("a"));
        assertFalse(Strings.isBlank(" a "));
    }

    @Test
    void blankCheckLooksAtCodePointsNotChars() {
        assertFalse(Strings.isBlank("😀"), "a surrogate pair is not whitespace");
    }

    @Test
    void trimToNullTreatsBlankAsUnsetAndTrimsTheRest() {
        assertNull(Strings.trimToNull(null));
        assertNull(Strings.trimToNull("   "));
        assertNull(Strings.trimToNull(" "));
        assertEquals("x", Strings.trimToNull(" x \t"));
    }

    @Test
    void trimToNullTrimsLikeStringTrimAsTheOriginalCodeDid() {
        // The original checked isBlank() and then called trim(), which only removes chars <= U+0020.
        assertEquals(" x ", Strings.trimToNull(" x "));
    }

    @Test
    void urlEncodeUsesUtf8FormEncoding() {
        assertEquals("%2Fapi", Strings.urlEncode("/api"));
        assertEquals("a+b", Strings.urlEncode("a b"));
        assertEquals("%C3%A9", Strings.urlEncode("é"));
        assertEquals("a%26b%3Dc", Strings.urlEncode("a&b=c"));
    }
}
