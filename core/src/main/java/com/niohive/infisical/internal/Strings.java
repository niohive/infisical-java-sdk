package com.niohive.infisical.internal;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** Java 8 versions of the few String helpers the library needs. */
public final class Strings {

    private Strings() {
    }

    /** True for null, empty, or only whitespace; the same meaning as Java 11's {@code String.isBlank()}. */
    public static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    /** Null when blank, otherwise {@code value.trim()}. */
    public static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /** UTF-8 form encoding, as used in query strings. */
    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }
}
