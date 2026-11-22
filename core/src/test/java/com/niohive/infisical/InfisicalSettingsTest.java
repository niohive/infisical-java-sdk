package com.niohive.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InfisicalSettingsTest {

    private final Map<String, String> env = new HashMap<>();

    @Test
    void keyNamesAndDefaults() {
        assertEquals("INFISICAL_URL", InfisicalSettings.INFISICAL_URL);
        assertEquals("INFISICAL_CLIENT_ID", InfisicalSettings.INFISICAL_CLIENT_ID);
        assertEquals("INFISICAL_CLIENT_SECRET", InfisicalSettings.INFISICAL_CLIENT_SECRET);
        assertEquals("INFISICAL_PROJECT", InfisicalSettings.INFISICAL_PROJECT);
        assertEquals("INFISICAL_ENVIRONMENT", InfisicalSettings.INFISICAL_ENVIRONMENT);
        assertEquals("INFISICAL_SECRET_PATH", InfisicalSettings.INFISICAL_SECRET_PATH);
        assertEquals("https://app.infisical.com", InfisicalSettings.DEFAULT_URL);
        assertEquals("/", InfisicalSettings.DEFAULT_SECRET_PATH);
    }

    @Test
    void readsEveryKey() {
        configure();
        env.put("INFISICAL_URL", "https://infisical.example");
        env.put("INFISICAL_SECRET_PATH", "/api");

        InfisicalSettings s = from();

        assertEquals("https://infisical.example", s.url());
        assertEquals("id", s.clientId());
        assertEquals("secret", s.clientSecret());
        assertEquals("project", s.project());
        assertEquals("dev", s.environment());
        assertEquals("/api", s.secretPath());
    }

    @Test
    void skippedWhenClientIdIsUnsetOrBlankEvenIfTheRestIsBroken() {
        env.put("INFISICAL_URL", "ftp://broken");
        assertFalse(InfisicalSettings.from(env::get).isPresent());
        env.put("INFISICAL_CLIENT_ID", " \t ");
        assertFalse(InfisicalSettings.from(env::get).isPresent());
    }

    @Test
    void partialConfigurationListsEveryMissingKeyInOrder() {
        env.put("INFISICAL_CLIENT_ID", "id");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> InfisicalSettings.from(env::get));
        assertEquals("INFISICAL_CLIENT_ID is set but these are missing: "
                + "INFISICAL_CLIENT_SECRET, INFISICAL_PROJECT, INFISICAL_ENVIRONMENT", e.getMessage());
    }

    @Test
    void partialConfigurationListsOnlyTheMissingKey() {
        configure();
        env.put("INFISICAL_PROJECT", "   ");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> InfisicalSettings.from(env::get));
        assertEquals("INFISICAL_CLIENT_ID is set but these are missing: INFISICAL_PROJECT", e.getMessage());
    }

    @Test
    void valuesAreTrimmed() {
        env.put("INFISICAL_URL", " https://infisical.example ");
        env.put("INFISICAL_CLIENT_ID", " id ");
        env.put("INFISICAL_CLIENT_SECRET", "\tsecret\n");
        env.put("INFISICAL_PROJECT", " project ");
        env.put("INFISICAL_ENVIRONMENT", " dev ");
        env.put("INFISICAL_SECRET_PATH", " /api ");

        InfisicalSettings s = from();

        assertEquals("https://infisical.example", s.url());
        assertEquals("id", s.clientId());
        assertEquals("secret", s.clientSecret());
        assertEquals("project", s.project());
        assertEquals("dev", s.environment());
        assertEquals("/api", s.secretPath());
    }

    @Test
    void blankUrlAndPathUseTheDefaults() {
        configure();
        env.put("INFISICAL_URL", "  ");
        env.put("INFISICAL_SECRET_PATH", "");

        InfisicalSettings s = from();

        assertEquals("https://app.infisical.com", s.url());
        assertEquals("/", s.secretPath());
    }

    @Test
    void trailingSlashesAreRemovedFromTheUrl() {
        configure();
        env.put("INFISICAL_URL", "http://infisical.internal:8080/base//");
        assertEquals("http://infisical.internal:8080/base", from().url());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://infisical.example",
        "file:/etc/passwd",
        "jar:file:/a.jar!/b",
        "infisical.example",
        "not a url",
        "https://",
        "https:///path",
        "https://u:p@infisical.example",
        "https://infisical.example?x=1",
        "https://infisical.example#x",
    })
    void urlMustBeHttpOrHttpsWithAHostAndNothingSurprising(String url) {
        configure();
        env.put("INFISICAL_URL", url);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> InfisicalSettings.from(env::get));
        assertTrue(e.getMessage().startsWith("INFISICAL_URL "), e.getMessage());
        assertFalse(e.getMessage().contains(url), "the value is never echoed: " + e.getMessage());
    }

    @Test
    void schemeCaseDoesNotMatter() {
        configure();
        env.put("INFISICAL_URL", "HTTPS://infisical.example");
        assertEquals("HTTPS://infisical.example", from().url());
    }

    @Test
    void toStringShowsWhereButNeverTheCredentials() {
        env.put("INFISICAL_URL", "https://url.example");
        env.put("INFISICAL_CLIENT_ID", "CLIENT-ID-MARKER");
        env.put("INFISICAL_CLIENT_SECRET", "CLIENT-SECRET-MARKER");
        env.put("INFISICAL_PROJECT", "project-marker");
        env.put("INFISICAL_ENVIRONMENT", "env-marker");
        env.put("INFISICAL_SECRET_PATH", "/path-marker");

        String text = from().toString();

        assertEquals("InfisicalSettings[url=https://url.example, project=project-marker, "
                + "environment=env-marker, secretPath=/path-marker]", text);
        assertFalse(text.contains("CLIENT-ID-MARKER"));
        assertFalse(text.contains("CLIENT-SECRET-MARKER"));
    }

    @Test
    void fromSystemEnvironmentIsEmptyWhenTheProcessHasNoClientId() {
        assumeTrue(System.getenv("INFISICAL_CLIENT_ID") == null, "INFISICAL_CLIENT_ID is set in this process");
        assertEquals(Optional.empty(), InfisicalSettings.fromSystemEnvironment());
    }

    @Test
    void lookupIsRequired() {
        assertThrows(NullPointerException.class, () -> InfisicalSettings.from(null));
    }

    private void configure() {
        env.put("INFISICAL_CLIENT_ID", "id");
        env.put("INFISICAL_CLIENT_SECRET", "secret");
        env.put("INFISICAL_PROJECT", "project");
        env.put("INFISICAL_ENVIRONMENT", "dev");
    }

    private InfisicalSettings from() {
        return InfisicalSettings.from(env::get).orElseThrow(IllegalStateException::new);
    }
}
