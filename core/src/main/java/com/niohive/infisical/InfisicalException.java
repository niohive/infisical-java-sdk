package com.niohive.infisical;

/**
 * Infisical couldn't be reached, rejected a request, or answered with something unreadable.
 * Messages never contain secrets, the client ID or the client secret.
 */
public final class InfisicalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InfisicalException(String message) {
        super(message);
    }

    public InfisicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
