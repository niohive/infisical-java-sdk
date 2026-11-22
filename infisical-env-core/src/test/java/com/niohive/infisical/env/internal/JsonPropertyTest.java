package com.niohive.infisical.env.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Randomised checks with a fixed seed, so any failure can be reproduced. */
class JsonPropertyTest {

    private static final long SEED = 42L;
    private static final int RUNS = 10_000;

    private static final String VALID_RESPONSE = "{\"imports\":[{\"secretPath\":\"/shared\",\"secrets\":["
            + "{\"secretKey\":\"A\",\"secretValue\":\"x\\\"y\\u00e9\"}]}],"
            + "\"secrets\":[{\"id\":\"1\",\"secretKey\":\"B\",\"secretValue\":\"2\",\"version\":-1.5e3,"
            + "\"tags\":[true,false,null,{}]}]}";

    @Test
    void writeThenReadGivesBackTheSameStrings() {
        Random random = new Random(SEED);
        for (int run = 0; run < RUNS; run++) {
            Map<String, String> original = new LinkedHashMap<>();
            int fields = random.nextInt(4);
            for (int f = 0; f < fields; f++) {
                original.put(randomString(random), randomString(random));
            }
            Map<String, String> read = Json.readTopLevel(Json.writeObject(original));
            assertEquals(original, read, "run " + run);
        }
    }

    @Test
    void mutatedResponsesGiveAMapOrAParseErrorAndNothingElse() {
        Random random = new Random(SEED);
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            for (int run = 0; run < RUNS; run++) {
                String input = mutate(VALID_RESPONSE, random);
                try {
                    assertNotNull(Json.readSecrets(input));
                } catch (JsonParseException expected) {
                    // the only allowed failure
                }
            }
        });
    }

    private static String randomString(Random random) {
        StringBuilder b = new StringBuilder();
        int length = random.nextInt(12);
        for (int i = 0; i < length; i++) {
            switch (random.nextInt(6)) {
                case 0:
                    b.append((char) random.nextInt(0x20)); // control characters
                    break;
                case 1:
                    b.append("\"\\/  ".charAt(random.nextInt(5)));
                    break;
                case 2:
                    b.appendCodePoint(0x10000 + random.nextInt(0x100000)); // outside the BMP
                    break;
                case 3:
                    b.append((char) (0xD800 + random.nextInt(0x800))); // lone surrogates
                    break;
                default:
                    b.append((char) (0x20 + random.nextInt(0x7FE0)));
                    break;
            }
        }
        return b.toString();
    }

    private static String mutate(String json, Random random) {
        StringBuilder b = new StringBuilder(json);
        int edits = 1 + random.nextInt(4);
        String alphabet = "{}[]\",:\\ 0-.eE+tfnu\u0000\uD800";
        for (int e = 0; e < edits && b.length() > 0; e++) {
            int at = random.nextInt(b.length());
            char c = alphabet.charAt(random.nextInt(alphabet.length()));
            switch (random.nextInt(3)) {
                case 0:
                    b.setCharAt(at, c);
                    break;
                case 1:
                    b.insert(at, c);
                    break;
                default:
                    b.deleteCharAt(at);
                    break;
            }
        }
        return b.toString();
    }
}
