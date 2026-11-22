package com.niohive.infisical.env;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake Infisical for tests: the login and list-secrets endpoints on 127.0.0.1, with configurable answers,
 * a record of every request, and failure modes. Like self-hosted Infisical, it answers any request that
 * carries an {@code Upgrade} header with 405 (the h2c regression).
 */
public final class FakeInfisical implements AutoCloseable {

    public static final String LOGIN_PATH = "/api/v1/auth/universal-auth/login";
    public static final String SECRETS_PATH = "/api/v3/secrets/raw";
    public static final String TOKEN = "test-token";
    public static final int HUGE_BODY_BYTES = 16 * 1024 * 1024 + 1;

    /** How the login endpoint misbehaves. */
    public enum Mode {
        NORMAL,
        /** Waits 5 s before answering. */
        SLOW,
        /** Sends a 200 and then one byte every 100 ms, for up to 10 s. */
        DRIP,
        /** Answers 302 to a second server, which counts its hits. */
        REDIRECT,
        /** Answers 200 with a body one byte over 16 MiB. */
        HUGE,
        /** Answers the login with 401, {@code WWW-Authenticate: Basic} and {"message":"bad creds"}. */
        WWW_AUTHENTICATE,
        /** Logs in normally, then answers the list call with 401, {@code WWW-Authenticate: Basic} and {"message":"bad creds"}. */
        LIST_CHALLENGE
    }

    /** One recorded request. Header names are lower case. */
    public static final class Request {
        public final String method;
        public final String path;
        public final String rawQuery;
        public final Map<String, List<String>> headers;
        public final String body;

        Request(String method, String path, String rawQuery, Map<String, List<String>> headers, String body) {
            this.method = method;
            this.path = path;
            this.rawQuery = rawQuery;
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpServer server;
    private final HttpServer redirectTarget;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();

    private volatile Mode mode = Mode.NORMAL;
    private volatile int loginStatus = 200;
    private volatile String loginBody = "{\"accessToken\":\"" + TOKEN + "\",\"expiresIn\":7200}";
    private volatile int secretsStatus = 200;
    private volatile String secretsBody = "{\"secrets\":[]}";

    public FakeInfisical() throws IOException {
        InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        redirectTarget = HttpServer.create(loopback, 0);
        redirectTarget.createContext("/", exchange -> {
            redirectTargetHits.incrementAndGet();
            respond(exchange, 200, loginBody);
        });
        redirectTarget.setExecutor(executor);
        redirectTarget.start();

        server = HttpServer.create(loopback, 0);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /** {@code INFISICAL_*} settings pointing at this server; change entries as needed. */
    public Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("INFISICAL_URL", url());
        env.put("INFISICAL_CLIENT_ID", "client-id");
        env.put("INFISICAL_CLIENT_SECRET", "client-secret");
        env.put("INFISICAL_PROJECT", "project-id");
        env.put("INFISICAL_ENVIRONMENT", "dev");
        return env;
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeInfisical mode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public FakeInfisical login(int status, String body) {
        this.loginStatus = status;
        this.loginBody = body;
        return this;
    }

    public FakeInfisical secrets(int status, String body) {
        this.secretsStatus = status;
        this.secretsBody = body;
        return this;
    }

    public List<Request> requests() {
        return Collections.unmodifiableList(new ArrayList<>(requests));
    }

    public Request lastRequest(String path) {
        Request last = null;
        for (Request r : requests) {
            if (r.path.equals(path)) {
                last = r;
            }
        }
        return last;
    }

    public int redirectTargetHits() {
        return redirectTargetHits.get();
    }

    @Override
    public void close() {
        server.stop(0);
        redirectTarget.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            Map<String, List<String>> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) {
                headers.put(h.getKey().toLowerCase(Locale.ROOT), new ArrayList<>(h.getValue()));
            }
            requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                    headers, readBody(exchange.getRequestBody())));

            if (exchange.getRequestHeaders().containsKey("Upgrade")) {
                respond(exchange, 405, "{\"message\":\"h2c upgrade not supported\"}");
            } else if (path.equals(LOGIN_PATH) && "POST".equals(exchange.getRequestMethod())) {
                handleLogin(exchange);
            } else if (path.equals(SECRETS_PATH) && "GET".equals(exchange.getRequestMethod())) {
                if (mode == Mode.LIST_CHALLENGE) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"infisical\"");
                    respond(exchange, 401, "{\"message\":\"bad creds\"}");
                } else if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    respond(exchange, 401, "{\"message\":\"wrong token\"}");
                } else {
                    respond(exchange, secretsStatus, secretsBody);
                }
            } else {
                respond(exchange, 404, "{\"message\":\"not found\"}");
            }
        } finally {
            exchange.close();
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        switch (mode) {
            case SLOW:
                sleep(5_000);
                respond(exchange, loginStatus, loginBody);
                break;
            case DRIP:
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < 100; i++) {
                        out.write(' ');
                        out.flush();
                        sleep(100);
                    }
                } catch (IOException clientWentAway) {
                    // expected once the client gives up
                }
                break;
            case REDIRECT:
                exchange.getResponseHeaders().add("Location",
                        "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + LOGIN_PATH);
                respond(exchange, 302, "");
                break;
            case HUGE:
                // Valid JSON with a usable token, so only the size cap can reject it.
                byte[] head = ("{\"accessToken\":\"" + TOKEN + "\",\"pad\":\"").getBytes(StandardCharsets.UTF_8);
                byte[] tail = "\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, HUGE_BODY_BYTES);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(head);
                    byte[] chunk = new byte[64 * 1024];
                    java.util.Arrays.fill(chunk, (byte) 'a');
                    for (int left = HUGE_BODY_BYTES - head.length - tail.length; left > 0; left -= chunk.length) {
                        out.write(chunk, 0, Math.min(chunk.length, left));
                    }
                    out.write(tail);
                } catch (IOException clientWentAway) {
                    // expected once the client stops reading
                }
                break;
            case WWW_AUTHENTICATE:
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"infisical\"");
                respond(exchange, 401, "{\"message\":\"bad creds\"}");
                break;
            default:
                respond(exchange, loginStatus, loginBody);
                break;
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) != -1; ) {
            bytes.write(buffer, 0, n);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
