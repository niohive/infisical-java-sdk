package com.niohive.infisical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.niohive.infisical.FakeInfisical.Mode;
import com.niohive.infisical.FakeInfisical.Request;
import java.io.IOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InfisicalClientTest {

    private static final int TIMEOUT_MILLIS = 500;

    private FakeInfisical fake;
    private Map<String, String> env;
    private final InfisicalClient client = new InfisicalClient(TIMEOUT_MILLIS);

    @BeforeEach
    void startFake() throws IOException {
        fake = new FakeInfisical();
        env = fake.env();
    }

    @AfterEach
    void stopFake() {
        fake.close();
    }

    // ---- B7: login ----

    @Test
    void logsInWithAJsonBody() {
        env.put("INFISICAL_CLIENT_SECRET", "se\"cr\\et");

        client.fetchSecrets(settings());

        Request login = fake.lastRequest(FakeInfisical.LOGIN_PATH);
        assertEquals("POST", login.method);
        assertEquals("application/json", login.header("Content-Type"));
        assertEquals("{\"clientId\":\"client-id\",\"clientSecret\":\"se\\\"cr\\\\et\"}", login.body);
    }

    @Test
    void missingOrBlankAccessTokenFails() {
        fake.login(200, "{\"expiresIn\":7200}");
        assertMessage("Infisical login returned no access token");
        fake.login(200, "{\"accessToken\":\"  \"}");
        assertMessage("Infisical login returned no access token");
        fake.login(200, "{\"accessToken\":null}");
        assertMessage("Infisical login returned no access token");
    }

    @Test
    void anAccessTokenThatCannotBeAHeaderValueIsUnreadableAndNeverEchoed() {
        String[] tokens = {"TOKEN-MARKER\\r\\nX-Injected: 1", "TOKEN MARKER", "TOKEN-MARKER\\u00e9"};
        for (String token : tokens) {
            fake.login(200, "{\"accessToken\":\"" + token + "\"}");
            InfisicalException e = assertThrows(InfisicalException.class, () -> client.fetchSecrets(settings()));
            assertEquals("Infisical returned an unreadable response to log in", e.getMessage());
            assertNoMarker(e, "TOKEN");
        }
        assertNull(fake.lastRequest(FakeInfisical.SECRETS_PATH), "no list request was sent");
    }

    // ---- B8: list call ----

    @Test
    void listsSecretsWithTheExactQueryAndBearerToken() {
        env.put("INFISICAL_SECRET_PATH", "/api");

        client.fetchSecrets(settings());

        Request list = fake.lastRequest(FakeInfisical.SECRETS_PATH);
        assertEquals("GET", list.method);
        assertEquals("workspaceId=project-id&environment=dev&secretPath=%2Fapi"
                + "&expandSecretReferences=true&includeImports=true&recursive=false", list.rawQuery);
        assertEquals("Bearer " + FakeInfisical.TOKEN, list.header("Authorization"));
    }

    // ---- B9: transport ----

    @Test
    void everyRequestAcceptsJsonAndNeverAsksForAnH2cUpgrade() {
        client.fetchSecrets(settings());

        assertEquals(2, fake.requests().size());
        for (Request r : fake.requests()) {
            assertEquals("application/json", r.header("Accept"), r.path);
            assertNull(r.header("Upgrade"), r.path);
        }
    }

    @Test
    void aSlowServerTimesOut() {
        fake.mode(Mode.SLOW);
        long start = System.nanoTime();
        assertMessageStartsWith("Couldn't reach Infisical to log in: ");
        assertTrue(millisSince(start) < 3_000, "took " + millisSince(start) + " ms");
    }

    @Test
    void aServerThatDripsBytesHitsTheOverallDeadline() {
        fake.mode(Mode.DRIP);
        long start = System.nanoTime();
        assertMessageStartsWith("Couldn't reach Infisical to log in: ");
        assertTrue(millisSince(start) < 3_000, "took " + millisSince(start) + " ms");
    }

    @Test
    void aServerThatDripsHeadersHitsTheOverallDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Thread dripper = new Thread(() -> {
                try (java.net.Socket socket = server.accept()) {
                    java.io.OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    for (int i = 0; i < 100; i++) {
                        out.write("X-A: b\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        out.flush();
                        Thread.sleep(100);
                    }
                } catch (Exception clientWentAway) {
                    // expected once the client gives up
                }
            });
            dripper.setDaemon(true);
            dripper.start();
            env.put("INFISICAL_URL", "http://127.0.0.1:" + server.getLocalPort());

            long start = System.nanoTime();
            assertMessageStartsWith("Couldn't reach Infisical to log in: ");
            assertTrue(millisSince(start) < 3_000, "took " + millisSince(start) + " ms");
        }
    }

    @Test
    void aJvmWideAuthenticatorNeverSendsItsCredentialsToInfisical() {
        assumeFalse("1.8".equals(System.getProperty("java.specification.version")),
                "Java 8 has no per-connection authenticator; documented in the README");
        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication("corp-user", "CORP-PASSWORD".toCharArray());
            }
        });
        try {
            fake.mode(Mode.WWW_AUTHENTICATE);
            assertMessage("Infisical failed to log in (HTTP 401): bad creds");
            fake.mode(Mode.LIST_CHALLENGE);
            assertMessage("Infisical failed to list secrets (HTTP 401): bad creds");
        } finally {
            Authenticator.setDefault(null);
        }
        assertEquals(3, fake.requests().size(), "no retries with credentials");
        for (Request r : fake.requests()) {
            String auth = r.header("Authorization");
            assertTrue(auth == null || auth.equals("Bearer " + FakeInfisical.TOKEN), r.path + " " + auth);
        }
    }

    @Test
    void redirectsAreNeverFollowed() {
        fake.mode(Mode.REDIRECT);
        assertMessage("Infisical failed to log in (HTTP 302)");
        assertEquals(0, fake.redirectTargetHits());
    }

    // ---- B10: merge through the wire ----

    @Test
    void importsComeFirstAndTheFolderWins() {
        fake.secrets(200, "{\"secrets\":[{\"secretKey\":\"B\",\"secretValue\":\"folder\"}],"
                + "\"imports\":[{\"secrets\":[{\"secretKey\":\"A\",\"secretValue\":\"1\"},"
                + "{\"secretKey\":\"B\",\"secretValue\":\"import\"}]},"
                + "{\"secrets\":[{\"secretKey\":\"A\",\"secretValue\":\"2\"},{\"secretKey\":\" \",\"secretValue\":\"x\"}]}]}");

        Map<String, String> secrets = client.fetchSecrets(settings());

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("A", "2");
        expected.put("B", "folder");
        assertEquals(expected, secrets);
        assertThrows(UnsupportedOperationException.class, () -> secrets.put("C", "x"));
    }

    @Test
    void utf8ValuesSurviveTheRoundTrip() {
        fake.secrets(200, "{\"secrets\":[{\"secretKey\":\"K\",\"secretValue\":\"é€😀\"}]}");
        assertEquals("é€😀", client.fetchSecrets(settings()).get("K"));
    }

    // ---- B11: error responses ----

    @Test
    void errorResponseIncludesInfisicalsMessage() {
        fake.login(401, "{\"message\":\"bad creds\"}");
        assertMessage("Infisical failed to log in (HTTP 401): bad creds");
    }

    @Test
    void listErrorNamesTheListAction() {
        fake.secrets(403, "{\"message\":\"no access\"}");
        assertMessage("Infisical failed to list secrets (HTTP 403): no access");
    }

    @Test
    void errorResponseWithoutAUsableMessageHasNoSuffix() {
        fake.login(500, "");
        assertMessage("Infisical failed to log in (HTTP 500)");
        fake.login(500, "<html>oops</html>");
        assertMessage("Infisical failed to log in (HTTP 500)");
        fake.login(500, "{\"message\":\"  \"}");
        assertMessage("Infisical failed to log in (HTTP 500)");
        fake.login(500, "{\"message\":{\"nested\":true}}");
        assertMessage("Infisical failed to log in (HTTP 500)");
    }

    @Test
    void infisicalsMessageIsCutAndStrippedOfControlCharacters() {
        StringBuilder message = new StringBuilder("line1\\r\\nline2\\u0000\\u2028");
        while (message.length() < 400) {
            message.append('x');
        }
        fake.login(400, "{\"message\":\"" + message + "\"}");

        InfisicalException e = assertThrows(InfisicalException.class, () -> client.fetchSecrets(settings()));

        String suffix = e.getMessage().substring("Infisical failed to log in (HTTP 400): ".length());
        assertTrue(suffix.startsWith("line1line2x"), suffix);
        assertEquals(200, suffix.length());
        for (char c : suffix.toCharArray()) {
            assertFalse(Character.isISOControl(c) || c == ' ' || c == ' ', "char " + (int) c);
        }
    }

    @Test
    void infisicalsMessageLosesInvisibleFormatCharacters() {
        fake.login(400, "{\"message\":\"admin\\u202Eexe.\\u200Btxt\"}");
        assertMessage("Infisical failed to log in (HTTP 400): adminexe.txt");
    }

    @Test
    void wwwAuthenticateChallengeStillGivesTheNormalError() {
        fake.mode(Mode.WWW_AUTHENTICATE);
        assertMessage("Infisical failed to log in (HTTP 401): bad creds");
    }

    // ---- B12: failures ----

    @Test
    void unreachableServer() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        env.put("INFISICAL_URL", "http://127.0.0.1:" + closedPort);
        assertMessageStartsWith("Couldn't reach Infisical to log in: ");
    }

    @Test
    void interruptedThreadFailsWithoutAnyRequestAndKeepsTheFlag() {
        Thread.currentThread().interrupt();
        try {
            assertMessage("Interrupted while trying to log in in Infisical");
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt flag is kept");
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, fake.requests().size());
    }

    @Test
    void unreadableSuccessBodyIsReportedWithoutTheBody() {
        fake.secrets(200, "{\"secrets\":[{\"secretKey\":\"K\",\"secretValue\":\"SECRET-VALUE\"");
        InfisicalException e = assertThrows(InfisicalException.class, () -> client.fetchSecrets(settings()));
        assertEquals("Infisical returned an unreadable response to list secrets", e.getMessage());
        assertNoMarker(e, "SECRET-VALUE");

        fake.login(200, "not json SECRET-VALUE");
        e = assertThrows(InfisicalException.class, () -> client.fetchSecrets(settings()));
        assertEquals("Infisical returned an unreadable response to log in", e.getMessage());
        assertNoMarker(e, "SECRET-VALUE");
    }

    @Test
    void emptySuccessBodyIsUnreadable() {
        fake.secrets(200, "");
        assertMessage("Infisical returned an unreadable response to list secrets");
    }

    @Test
    void oversizedBodyIsUnreadable() {
        fake.mode(Mode.HUGE);
        assertMessage("Infisical returned an unreadable response to log in");
    }

    // ---- other ----

    @Test
    void credentialsNeverAppearInAnyFailureMessage() {
        env.put("INFISICAL_CLIENT_ID", "CLIENT-ID-MARKER");
        env.put("INFISICAL_CLIENT_SECRET", "CLIENT-SECRET-MARKER");
        Mode[] modes = {Mode.REDIRECT, Mode.HUGE, Mode.WWW_AUTHENTICATE};
        for (Mode mode : modes) {
            fake.mode(mode);
            InfisicalException e = assertThrows(InfisicalException.class, () -> client.fetchSecrets(settings()));
            assertNoMarker(e, "CLIENT-ID-MARKER");
            assertNoMarker(e, "CLIENT-SECRET-MARKER");
        }
    }

    @Test
    void oneClientServesManyThreadsAtOnce() throws Exception {
        fake.secrets(200, "{\"secrets\":[{\"secretKey\":\"K\",\"secretValue\":\"V\"}]}");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Map<String, String>>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> client.fetchSecrets(settings()));
            }
            for (Future<Map<String, String>> result : pool.invokeAll(calls)) {
                assertEquals("V", result.get().get("K"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void readmeQuickStart() {
        fake.secrets(200, "{\"secrets\":[{\"secretKey\":\"DB_PASSWORD\",\"secretValue\":\"pw\"}]}");

        // The README's quick start, with env::get in place of the process environment.
        Map<String, String> secrets = InfisicalSettings.from(env::get)
                .map(new InfisicalClient()::fetchSecrets)
                .orElse(Collections.emptyMap());

        assertEquals("pw", secrets.get("DB_PASSWORD"));
        env.remove("INFISICAL_CLIENT_ID");
        assertEquals(Collections.emptyMap(), InfisicalSettings.from(env::get)
                .map(new InfisicalClient()::fetchSecrets)
                .orElse(Collections.emptyMap()));
    }

    @Test
    void defaultClientWorks() {
        fake.secrets(200, "{\"secrets\":[{\"secretKey\":\"K\",\"secretValue\":\"V\"}]}");
        assertEquals("V", new InfisicalClient().fetchSecrets(settings()).get("K"));
    }

    @Test
    void settingsAreRequired() {
        assertThrows(NullPointerException.class, () -> client.fetchSecrets(null));
    }

    private InfisicalSettings settings() {
        return InfisicalSettings.from(env::get).orElseThrow(IllegalStateException::new);
    }

    private void assertMessage(String expected) {
        InfisicalException e = assertThrows(InfisicalException.class, () -> client.fetchSecrets(settings()));
        assertEquals(expected, e.getMessage());
    }

    private void assertMessageStartsWith(String prefix) {
        InfisicalException e = assertThrows(InfisicalException.class, () -> client.fetchSecrets(settings()));
        assertTrue(e.getMessage().startsWith(prefix), e.getMessage());
    }

    private static void assertNoMarker(Throwable e, String marker) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains(marker), t.toString());
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
