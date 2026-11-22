package com.niohive.infisical.env;

import com.niohive.infisical.env.internal.Http;
import com.niohive.infisical.env.internal.Json;
import com.niohive.infisical.env.internal.JsonParseException;
import com.niohive.infisical.env.internal.Strings;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Logs in to Infisical with a machine identity (Universal Auth) and lists a folder's secrets, including
 * imported ones. Stateless and thread-safe: nothing, not even the access token, is kept between calls.
 * Uses the JVM's default TLS and proxy settings ({@code https.proxyHost}, {@code javax.net.ssl.trustStore}).
 */
public final class InfisicalClient {

    static final int DEFAULT_TIMEOUT_MILLIS = 15_000;
    private static final int MAX_MESSAGE_LENGTH = 200;
    private static final String LOG_IN = "log in";
    private static final String LIST_SECRETS = "list secrets";

    private final int timeoutMillis;

    /** A client with 15 s connect, read and overall timeouts per request. */
    public InfisicalClient() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    InfisicalClient(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Logs in, then returns the folder's secrets as name to value. Imported secrets are added first, in order,
     * and the folder's own secrets win over them. The map is unmodifiable.
     *
     * @param settings where and how to connect
     * @return secret name to value
     * @throws InfisicalException when Infisical can't be reached, rejects a request, or answers unreadably
     */
    public Map<String, String> fetchSecrets(InfisicalSettings settings) {
        Objects.requireNonNull(settings, "settings");
        String token = login(settings);
        String query = "workspaceId=" + Strings.urlEncode(settings.project())
                + "&environment=" + Strings.urlEncode(settings.environment())
                + "&secretPath=" + Strings.urlEncode(settings.secretPath())
                + "&expandSecretReferences=true&includeImports=true&recursive=false";
        String body = send("GET", settings.url() + "/api/v3/secrets/raw?" + query,
                Collections.singletonMap("Authorization", "Bearer " + token), null, LIST_SECRETS);
        try {
            return Json.readSecrets(body);
        } catch (JsonParseException e) {
            throw unreadable(LIST_SECRETS, e);
        }
    }

    private String login(InfisicalSettings settings) {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("clientId", settings.clientId());
        credentials.put("clientSecret", settings.clientSecret());
        byte[] payload = Json.writeObject(credentials).getBytes(StandardCharsets.UTF_8);
        String body = send("POST", settings.url() + "/api/v1/auth/universal-auth/login",
                Collections.singletonMap("Content-Type", "application/json"), payload, LOG_IN);
        String token;
        try {
            token = Json.readTopLevel(body).get("accessToken");
        } catch (JsonParseException e) {
            throw unreadable(LOG_IN, e);
        }
        if (Strings.isBlank(token)) {
            throw new InfisicalException("Infisical login returned no access token");
        }
        if (!isVisibleAscii(token)) {
            // It goes into a header; anything else could inject headers, and the JDK's error would echo it.
            throw unreadable(LOG_IN, null);
        }
        return token;
    }

    private static boolean isVisibleAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private String send(String method, String url, Map<String, String> extraHeaders, byte[] body, String action) {
        if (Thread.interrupted()) {
            Thread.currentThread().interrupt();
            throw new InfisicalException("Interrupted while trying to " + action + " in Infisical");
        }
        Map<String, String> headers = new LinkedHashMap<>(extraHeaders);
        headers.put("Accept", "application/json");
        Http.Response response;
        try {
            response = Http.send(method, URI.create(url).toURL(), headers, body, timeoutMillis);
        } catch (Http.BodyTooLargeException e) {
            throw unreadable(action, e);
        } catch (IOException e) {
            throw new InfisicalException("Couldn't reach Infisical to " + action + ": " + e.getMessage(), e);
        }
        if (response.status / 100 != 2) {
            // Only Infisical's own message is shown; the body is never included otherwise.
            throw new InfisicalException("Infisical failed to " + action + " (HTTP " + response.status + ")"
                    + errorMessage(response.body));
        }
        return response.body;
    }

    private static InfisicalException unreadable(String action, Exception cause) {
        return new InfisicalException("Infisical returned an unreadable response to " + action, cause);
    }

    /**
     * ": message" when the body has a non-blank message; cut to 200 characters and stripped of control,
     * line-separator and invisible format characters (bidi overrides, zero-width), so it can't forge log lines.
     */
    private static String errorMessage(String body) {
        String message;
        try {
            message = Json.readTopLevel(body).get("message");
        } catch (JsonParseException e) {
            return "";
        }
        if (message == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < message.length() && clean.length() < MAX_MESSAGE_LENGTH; ) {
            int codePoint = message.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029
                    || Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            if (clean.length() + Character.charCount(codePoint) > MAX_MESSAGE_LENGTH) {
                break;
            }
            clean.appendCodePoint(codePoint);
        }
        return Strings.isBlank(clean.toString()) ? "" : ": " + clean;
    }
}
