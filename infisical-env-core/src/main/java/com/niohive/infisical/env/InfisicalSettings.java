package com.niohive.infisical.env;

import com.niohive.infisical.env.internal.Strings;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where to read secrets from and how to log in, read from the {@code INFISICAL_*} keys.
 * When {@code INFISICAL_CLIENT_ID} is unset, Infisical isn't configured and nothing is loaded.
 */
public final class InfisicalSettings {

    public static final String INFISICAL_URL = "INFISICAL_URL";
    public static final String INFISICAL_CLIENT_ID = "INFISICAL_CLIENT_ID";
    public static final String INFISICAL_CLIENT_SECRET = "INFISICAL_CLIENT_SECRET";
    public static final String INFISICAL_PROJECT = "INFISICAL_PROJECT";
    public static final String INFISICAL_ENVIRONMENT = "INFISICAL_ENVIRONMENT";
    public static final String INFISICAL_SECRET_PATH = "INFISICAL_SECRET_PATH";

    public static final String DEFAULT_URL = "https://app.infisical.com";
    public static final String DEFAULT_SECRET_PATH = "/";

    private final String url;
    private final String clientId;
    private final String clientSecret;
    private final String project;
    private final String environment;
    private final String secretPath;

    private InfisicalSettings(String url, String clientId, String clientSecret,
                              String project, String environment, String secretPath) {
        this.url = url;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.project = project;
        this.environment = environment;
        this.secretPath = secretPath;
    }

    /**
     * Reads the {@code INFISICAL_*} keys through {@code lookup}, for example {@code System::getenv},
     * {@code properties::getProperty} or Spring's {@code environment::getProperty}. Values are trimmed, and
     * blank values count as unset. A blank URL means {@value #DEFAULT_URL}, and a blank secret path means
     * {@value #DEFAULT_SECRET_PATH}.
     *
     * @return empty when {@code INFISICAL_CLIENT_ID} is unset
     * @throws IllegalStateException when the client ID is set but the client secret, project or environment
     *     is missing, or when the URL isn't an http or https URL with a host
     */
    public static Optional<InfisicalSettings> from(Function<String, String> lookup) {
        Objects.requireNonNull(lookup, "lookup");
        String clientId = Strings.trimToNull(lookup.apply(INFISICAL_CLIENT_ID));
        if (clientId == null) {
            return Optional.empty();
        }
        InfisicalSettings settings = new InfisicalSettings(
                orDefault(Strings.trimToNull(lookup.apply(INFISICAL_URL)), DEFAULT_URL).replaceAll("/+$", ""),
                clientId,
                Strings.trimToNull(lookup.apply(INFISICAL_CLIENT_SECRET)),
                Strings.trimToNull(lookup.apply(INFISICAL_PROJECT)),
                Strings.trimToNull(lookup.apply(INFISICAL_ENVIRONMENT)),
                orDefault(Strings.trimToNull(lookup.apply(INFISICAL_SECRET_PATH)), DEFAULT_SECRET_PATH));

        List<String> missing = new ArrayList<>();
        if (settings.clientSecret == null) {
            missing.add(INFISICAL_CLIENT_SECRET);
        }
        if (settings.project == null) {
            missing.add(INFISICAL_PROJECT);
        }
        if (settings.environment == null) {
            missing.add(INFISICAL_ENVIRONMENT);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(INFISICAL_CLIENT_ID + " is set but these are missing: "
                    + String.join(", ", missing));
        }
        if (!isUsableUrl(settings.url)) {
            // The value isn't echoed: a mistyped URL can contain credentials.
            throw new IllegalStateException(INFISICAL_URL
                    + " must be an http or https URL with a host, and no user info, query or fragment");
        }
        return Optional.of(settings);
    }

    /** {@code from(System::getenv)}. */
    public static Optional<InfisicalSettings> fromSystemEnvironment() {
        return from(System::getenv);
    }

    public String url() {
        return url;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String project() {
        return project;
    }

    public String environment() {
        return environment;
    }

    public String secretPath() {
        return secretPath;
    }

    /** Shows the URL, project, environment and secret path; never the client ID or client secret. */
    @Override
    public String toString() {
        return "InfisicalSettings[url=" + url + ", project=" + project + ", environment=" + environment
                + ", secretPath=" + secretPath + "]";
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static boolean isUsableUrl(String url) {
        try {
            URI uri = new URI(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
