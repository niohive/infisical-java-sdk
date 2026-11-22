package com.niohive.infisical.env.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * One HTTP/1.1 request over {@link HttpURLConnection}. It can't send the h2c {@code Upgrade} header that
 * self-hosted Infisical rejects, never follows redirects, caps the body size, and enforces an overall
 * deadline on top of the connect and read timeouts.
 */
public final class Http {

    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

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
     * @throws IOException when the server can't be reached or doesn't answer within {@code timeoutMillis}
     */
    public static Response send(String method, URL url, Map<String, String> headers, byte[] body,
                                int timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        boolean completed = false;
        try {
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
        } finally {
            if (!completed) {
                // Don't leave a half-read or still-streaming connection in the keep-alive pool.
                connection.disconnect();
            }
        }
    }

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
}
