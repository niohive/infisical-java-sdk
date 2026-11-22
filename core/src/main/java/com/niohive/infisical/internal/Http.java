package com.niohive.infisical.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One HTTP/1.1 request over {@link HttpURLConnection}. It can't send the h2c {@code Upgrade} header that
 * self-hosted Infisical rejects, never follows redirects, caps the body size, and closes the connection when
 * the overall deadline passes, however slowly the server sends its headers or body.
 */
public final class Http {

    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /**
     * Closes connections at their deadline, which stops a server that drips its status line or headers.
     * Its one daemon thread exits when idle.
     */
    private static final ScheduledThreadPoolExecutor WATCHDOG = newWatchdog();

    /**
     * {@code HttpURLConnection.setAuthenticator} (Java 9+), or null on Java 8. Each connection gets an
     * authenticator with no credentials, so a JVM-wide {@link Authenticator} set by the application is never
     * asked for credentials to send to Infisical.
     */
    private static final Method SET_AUTHENTICATOR = findSetAuthenticator();
    private static final Authenticator NO_CREDENTIALS = new Authenticator() {
    };

    private Http() {
    }

    /** A status code and the body decoded as UTF-8. */
    public static final class Response {
        public final int status;
        public final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** The response body was larger than {@link #MAX_BODY_BYTES}. */
    public static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLargeException() {
            super("Response body is larger than " + MAX_BODY_BYTES + " bytes");
        }
    }

    /**
     * Sends the request and reads the whole response.
     *
     * @param body the request body, or null for none
     * @throws BodyTooLargeException when the response body is over the cap
     * @throws IOException when the server can't be reached or the whole exchange takes over {@code timeoutMillis}
     */
    public static Response send(String method, URL url, Map<String, String> headers, byte[] body,
                                int timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            connection.disconnect();
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        boolean completed = false;
        try {
            withoutJvmWideCredentials(connection);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            InputStream in = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String text = in == null ? "" : readCapped(in, deadline);
            completed = true;
            return new Response(status, text);
        } catch (IOException | RuntimeException e) {
            if (timedOut.get() && !(e instanceof BodyTooLargeException)) {
                throw new SocketTimeoutException("No complete response within " + timeoutMillis + " ms");
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            if (!completed) {
                // Don't leave a half-read or still-streaming connection in the keep-alive pool.
                connection.disconnect();
            }
        }
    }

    /**
     * Reads the body up to the cap. The deadline is checked after every read because, on JDK 17 and later,
     * the watchdog's {@code disconnect()} doesn't interrupt a body read that's already under way.
     */
    private static String readCapped(InputStream in, long deadline) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = stream.read(buffer)) != -1; ) {
                if (bytes.size() + n > MAX_BODY_BYTES) {
                    throw new BodyTooLargeException();
                }
                bytes.write(buffer, 0, n);
                if (System.nanoTime() - deadline > 0) {
                    throw new SocketTimeoutException("Response took too long");
                }
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void withoutJvmWideCredentials(HttpURLConnection connection) {
        if (SET_AUTHENTICATOR == null) {
            return;
        }
        try {
            SET_AUTHENTICATOR.invoke(connection, NO_CREDENTIALS);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't isolate the connection from the JVM-wide Authenticator", e);
        }
    }

    private static Method findSetAuthenticator() {
        try {
            return HttpURLConnection.class.getMethod("setAuthenticator", Authenticator.class);
        } catch (NoSuchMethodException javaEight) {
            return null;
        }
    }

    private static ScheduledThreadPoolExecutor newWatchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "infisical-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setKeepAliveTime(1, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
