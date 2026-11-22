package com.niohive.infisical.env.internal;

/** Invalid JSON. The message gives a position and what was expected, and never quotes the input. */
public final class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    JsonParseException(String message) {
        super(message);
    }
}
