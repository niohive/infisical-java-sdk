package com.niohive.infisical.env.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.niohive.infisical.env.FakeInfisical;
import com.niohive.infisical.env.InfisicalClient;
import com.niohive.infisical.env.InfisicalException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class InfisicalEnvironmentPostProcessorTest {

    private static final String SECRETS = """
            {"secrets":[{"secretKey":"EDB_PASSWORD","secretValue":"from-folder"},
                        {"secretKey":"EDB_JWT_SECRET","secretValue":"jwt"}],
             "imports":[{"secretPath":"/shared","secrets":[{"secretKey":"EDB_PASSWORD","secretValue":"from-import"},
                                                          {"secretKey":"RESEND_FROM","secretValue":"a@b.co"}]}]}
            """;

    private FakeInfisical fake;
    private final RecordingLog log = new RecordingLog();

    @BeforeEach
    void startFake() throws IOException {
        fake = new FakeInfisical().secrets(200, SECRETS);
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    // ---- the 7 tests carried over from the original processor ----

    @Test
    void skipsWhenClientIdIsUnset() {
        StandardEnvironment env = environment(Map.of("INFISICAL_ENVIRONMENT", "dev"));

        postProcessor().postProcessEnvironment(env, null);

        assertFalse(env.getPropertySources().contains(InfisicalEnvironmentPostProcessor.PROPERTY_SOURCE_NAME));
        assertTrue(fake.requests().isEmpty());
        assertEquals(List.of("INFO Infisical not configured (INFISICAL_CLIENT_ID unset); using environment variables only"),
                log.lines);
    }

    @Test
    void failsFastWhenOnlyPartlyConfigured() {
        StandardEnvironment env = environment(Map.of("INFISICAL_CLIENT_ID", "id"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> postProcessor().postProcessEnvironment(env, null));

        assertTrue(e.getMessage().contains("INFISICAL_CLIENT_SECRET, INFISICAL_PROJECT, INFISICAL_ENVIRONMENT"));
        assertTrue(fake.requests().isEmpty());
    }

    @Test
    void loadsSecretsSoPlaceholdersResolve() {
        StandardEnvironment env = environment(configured());

        postProcessor().postProcessEnvironment(env, null);

        assertEquals("from-folder", env.resolvePlaceholders("${EDB_PASSWORD}"));
        assertEquals("jwt", env.getProperty("EDB_JWT_SECRET"), "Infisical wins over application.yaml");
        assertEquals("a@b.co", env.getProperty("RESEND_FROM"));
        String login = fake.lastRequest(FakeInfisical.LOGIN_PATH).body;
        assertTrue(login.contains("\"clientId\":\"client-abc\"") && login.contains("\"clientSecret\":\"s3cr3t-value\""));
        assertEquals("workspaceId=proj-1&environment=dev&secretPath=%2Fapi"
                + "&expandSecretReferences=true&includeImports=true&recursive=false",
                fake.lastRequest(FakeInfisical.SECRETS_PATH).rawQuery);
    }

    @Test
    void processEnvironmentVariablesWinOverInfisical() {
        Map<String, Object> vars = new HashMap<>(configured());
        vars.put("EDB_PASSWORD", "from-env");
        StandardEnvironment env = environment(vars);

        postProcessor().postProcessEnvironment(env, null);

        assertEquals("from-env", env.getProperty("EDB_PASSWORD"));
        assertEquals("jwt", env.getProperty("EDB_JWT_SECRET"));
    }

    @Test
    void defaultsSecretPathToRoot() {
        Map<String, Object> vars = new HashMap<>(configured());
        vars.put("INFISICAL_SECRET_PATH", "");

        postProcessor().postProcessEnvironment(environment(vars), null);

        assertTrue(fake.lastRequest(FakeInfisical.SECRETS_PATH).rawQuery.contains("&secretPath=%2F&"));
    }

    @Test
    void loginRejectionStopsStartupWithInfisicalsMessage() {
        fake.login(401, "{\"statusCode\":401,\"message\":\"Invalid credentials\"}");

        InfisicalException e = assertThrows(InfisicalException.class,
                () -> postProcessor().postProcessEnvironment(environment(configured()), null));

        assertEquals("Infisical failed to log in (HTTP 401): Invalid credentials", e.getMessage());
    }

    @Test
    void unreachableInfisicalStopsStartup() {
        Map<String, Object> vars = new HashMap<>(configured());
        fake.close();

        InfisicalException e = assertThrows(InfisicalException.class,
                () -> postProcessor().postProcessEnvironment(environment(vars), null));

        assertTrue(e.getMessage().startsWith("Couldn't reach Infisical to log in"), e.getMessage());
    }

    @Test
    void anUnresolvablePlaceholderInACredentialIsNeverEchoed() {
        Map<String, Object> vars = new HashMap<>(configured());
        vars.put("INFISICAL_CLIENT_SECRET", "SECRET-${MARKER}-x");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> postProcessor().postProcessEnvironment(environment(vars), null));

        assertEquals("INFISICAL_CLIENT_SECRET contains a ${...} placeholder that can't be resolved", e.getMessage());
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains("SECRET-"), t.toString());
        }
        assertTrue(fake.requests().isEmpty());
    }

    // ---- B13: placement ----

    @Test
    void infisicalSitsRightAfterTheSystemEnvironment() {
        StandardEnvironment env = environment(configured());

        postProcessor().postProcessEnvironment(env, null);

        List<String> names = env.getPropertySources().stream().map(PropertySource::getName).toList();
        assertEquals(List.of("systemEnvironment", "infisical", "application.yaml"), names);
    }

    @Test
    void infisicalGoesLastWithoutASystemEnvironment() {
        StandardEnvironment env = environment(configured());
        MutablePropertySources sources = env.getPropertySources();
        PropertySource<?> vars = sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.addFirst(new MapPropertySource("vars", ((SystemEnvironmentPropertySource) vars).getSource()));

        postProcessor().postProcessEnvironment(env, null);

        List<String> names = sources.stream().map(PropertySource::getName).toList();
        assertEquals(List.of("vars", "application.yaml", "infisical"), names);
    }

    // ---- B14: logs, and the plain-HTTP warning ----

    @Test
    void logsHowManySecretsWereLoadedAndWhereFromWithoutCredentials() {
        Map<String, Object> vars = new HashMap<>(configured());
        vars.put("INFISICAL_URL", fake.url().replace("http://127.0.0.1", "http://localhost"));

        postProcessor().postProcessEnvironment(environment(vars), null);

        String settings = "InfisicalSettings[url=" + vars.get("INFISICAL_URL")
                + ", project=proj-1, environment=dev, secretPath=/api]";
        assertEquals(List.of(
                "WARN Infisical URL uses plain HTTP; the client secret and secrets travel unencrypted",
                "INFO Loaded 3 secrets from Infisical (" + settings + ")"), log.lines);
        for (String line : log.lines) {
            assertFalse(line.contains("client-abc") || line.contains("s3cr3t-value"), line);
        }
    }

    @Test
    void noPlainHttpWarningForHttps() {
        Map<String, Object> vars = new HashMap<>(configured());
        vars.put("INFISICAL_URL", "https://127.0.0.1:1");

        assertThrows(InfisicalException.class, () -> postProcessor().postProcessEnvironment(environment(vars), null));

        assertTrue(log.lines.isEmpty(), log.lines.toString());
    }

    @Test
    void anEmptyFolderStillAddsTheSource() {
        fake.secrets(200, "{\"secrets\":[]}");
        StandardEnvironment env = environment(configured());

        postProcessor().postProcessEnvironment(env, null);

        assertTrue(env.getPropertySources().contains("infisical"));
        assertTrue(log.lines.get(log.lines.size() - 1).startsWith("INFO Loaded 0 secrets from Infisical ("));
    }

    private Map<String, Object> configured() {
        return Map.of(
                "INFISICAL_URL", fake.url() + "/",
                "INFISICAL_CLIENT_ID", "client-abc",
                "INFISICAL_CLIENT_SECRET", "s3cr3t-value",
                "INFISICAL_PROJECT", "proj-1",
                "INFISICAL_ENVIRONMENT", "dev",
                "INFISICAL_SECRET_PATH", "/api");
    }

    /** An environment whose only "system environment" is the given variables, plus application.yaml-style defaults. */
    private static StandardEnvironment environment(Map<String, Object> vars) {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addLast(new MapPropertySource("application.yaml", Map.of("EDB_JWT_SECRET", "yaml-default")));
        return env;
    }

    private InfisicalEnvironmentPostProcessor postProcessor() {
        return new InfisicalEnvironmentPostProcessor(log, new InfisicalClient());
    }
}
