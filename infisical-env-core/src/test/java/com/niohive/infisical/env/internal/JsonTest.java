package com.niohive.infisical.env.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    // ---- readTopLevel ----

    @Test
    void readsScalarFieldsAsText() {
        Map<String, String> m = Json.readTopLevel(
                "{\"accessToken\":\"abc\",\"expiresIn\":7200,\"ratio\":-1.5e3,\"ok\":true,\"no\":false}");
        assertEquals(map("accessToken", "abc", "expiresIn", "7200", "ratio", "-1.5e3", "ok", "true", "no", "false"), m);
    }

    @Test
    void nullAndNestedValuesAreAbsent() {
        Map<String, String> m = Json.readTopLevel("{\"a\":null,\"b\":{\"x\":1},\"c\":[1,2],\"d\":\"kept\"}");
        assertEquals(map("d", "kept"), m);
    }

    @Test
    void lastDuplicateKeyWins() {
        assertEquals(map("a", "2"), Json.readTopLevel("{\"a\":\"1\",\"a\":\"2\"}"));
        assertEquals(map(), Json.readTopLevel("{\"a\":\"1\",\"a\":null}"));
    }

    @Test
    void emptyObject() {
        assertEquals(map(), Json.readTopLevel("{}"));
        assertEquals(map(), Json.readTopLevel(" \t\r\n{ \t\r\n} \t\r\n"));
    }

    @Test
    void leadingByteOrderMarkIsSkipped() {
        assertEquals(map("a", "b"), Json.readTopLevel("\uFEFF{\"a\":\"b\"}"));
    }

    @Test
    void decodesEveryEscape() {
        Map<String, String> m = Json.readTopLevel(
                "{\"s\":\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u00e9 \\uD83D\\uDE00\"}");
        String s = m.get("s");
        assertEquals("\" \\ / \b \f \n \r \t \u00e9 \uD83D\uDE00", s);
        assertEquals(0x1F600, s.codePointAt(s.length() - 2), "a surrogate pair decodes to one code point");
    }

    @Test
    void escapedKeysAreDecoded() {
        assertEquals(map("a\"b", "c"), Json.readTopLevel("{\"a\\\"b\":\"c\"}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "   ",
        "[]",
        "\"text\"",
        "42",
        "null",
        "{",
        "{\"a\"}",
        "{\"a\":}",
        "{\"a\":1,}",
        "{,}",
        "{\"a\":1 \"b\":2}",
        "{\"a\":1}x",
        "{\"a\":1}{}",
        "{\"a\":\"unterminated}",
        "{\"a\":\"raw\u0001control\"}",
        "{\"a\":\"raw\nnewline\"}",
        "{\"a\":\"\\x\"}",
        "{\"a\":\"\\u12G4\"}",
        "{\"a\":\"\\u12\"}",
        "{\"a\":\"\\u\uFF10\uFF10\uFF14\uFF11\"}",
        "{\"a\":\"\\",
        "{\"a\":01}",
        "{\"a\":1.}",
        "{\"a\":-}",
        "{\"a\":.5}",
        "{\"a\":1e}",
        "{\"a\":1e+}",
        "{\"a\":+1}",
        "{'a':1}",
        "{\"a\":'b'}",
        "{\"a\":[1,]}",
        "{\"a\":[,1]}",
        "{a:1}",
        "{\"a\":1}//",
        "{/*c*/\"a\":1}",
        "{\"a\":NaN}",
        "{\"a\":Infinity}",
        "{\"a\":tru}",
        "{\"a\":nul}",
        "{\"a\":True}",
        "{\"a\"\u00A0:1}",
        "\uFEFF\uFEFF{}",
    })
    void rejectsInvalidJson(String input) {
        assertThrows(JsonParseException.class, () -> Json.readTopLevel(input));
    }

    @Test
    void acceptsNestingUpTo64Levels() {
        // The top-level object is level 1, so 63 nested arrays reach level 64.
        assertEquals(map(), Json.readTopLevel("{\"a\":" + repeat("[", 63) + repeat("]", 63) + "}"));
    }

    @Test
    void rejectsNestingBeyond64LevelsWithoutStackOverflow() {
        assertThrows(JsonParseException.class,
                () -> Json.readTopLevel("{\"a\":" + repeat("[", 64) + repeat("]", 64) + "}"));
        assertThrows(JsonParseException.class,
                () -> Json.readTopLevel("{\"a\":" + repeat("[", 100_000)));
    }

    @Test
    void errorMessagesNeverQuoteTheInput() {
        String[] inputs = {
            "{\"SECRET-MARKER\":\"SECRET-MARKER\" x}",
            "{\"a\":\"SECRET-MARKER\\q\"}",
            "{\"a\":SECRET-MARKER}",
            "{\"a\":\"SECRET-MARKER",
        };
        for (String input : inputs) {
            JsonParseException e = assertThrows(JsonParseException.class, () -> Json.readTopLevel(input));
            assertFalse(e.getMessage().contains("SECRET"), e.getMessage());
        }
    }

    @Test
    void readTopLevelResultIsUnmodifiable() {
        Map<String, String> m = Json.readTopLevel("{\"a\":\"b\"}");
        assertThrows(UnsupportedOperationException.class, () -> m.put("c", "d"));
    }

    // ---- readSecrets (B10) ----

    @Test
    void readsTheFolderSecrets() {
        String json = "{\"secrets\":[{\"secretKey\":\"A\",\"secretValue\":\"1\"},{\"secretKey\":\"B\",\"secretValue\":\"2\"}]}";
        assertEquals(map("A", "1", "B", "2"), Json.readSecrets(json));
    }

    @Test
    void laterImportsOverwriteEarlierOnesAndTheFolderOverwritesAllImports() {
        String imports = "\"imports\":["
                + "{\"secrets\":[{\"secretKey\":\"A\",\"secretValue\":\"import1\"},{\"secretKey\":\"B\",\"secretValue\":\"import1\"}]},"
                + "{\"secrets\":[{\"secretKey\":\"B\",\"secretValue\":\"import2\"},{\"secretKey\":\"C\",\"secretValue\":\"import2\"}]}"
                + "]";
        String secrets = "\"secrets\":[{\"secretKey\":\"C\",\"secretValue\":\"folder\"}]";
        Map<String, String> expected = map("A", "import1", "B", "import2", "C", "folder");

        assertEquals(expected, Json.readSecrets("{" + imports + "," + secrets + "}"), "imports first in the JSON");
        assertEquals(expected, Json.readSecrets("{" + secrets + "," + imports + "}"), "imports last in the JSON");
    }

    @Test
    void blankKeysAreSkippedAndMissingOrNullValuesBecomeEmpty() {
        String json = "{\"secrets\":["
                + "{\"secretKey\":\"\",\"secretValue\":\"x\"},"
                + "{\"secretKey\":\"  \",\"secretValue\":\"x\"},"
                + "{\"secretValue\":\"x\"},"
                + "{\"secretKey\":null,\"secretValue\":\"x\"},"
                + "{\"secretKey\":\"MISSING\"},"
                + "{\"secretKey\":\"NULL\",\"secretValue\":null},"
                + "{\"secretKey\":\"NUMBER\",\"secretValue\":42},"
                + "{\"secretKey\":\"NESTED\",\"secretValue\":{\"x\":1}}"
                + "]}";
        assertEquals(map("MISSING", "", "NULL", "", "NUMBER", "42", "NESTED", ""), Json.readSecrets(json));
    }

    @Test
    void missingArraysAndUnknownFieldsAreIgnored() {
        assertEquals(map(), Json.readSecrets("{}"));
        String json = "{\"extra\":{\"deep\":[1,{\"x\":null}]},\"imports\":[{\"secretPath\":\"/x\",\"secrets\":[]}],"
                + "\"secrets\":[{\"id\":\"1\",\"secretKey\":\"A\",\"secretValue\":\"1\",\"tags\":[],\"version\":3}]}";
        assertEquals(map("A", "1"), Json.readSecrets(json));
    }

    @Test
    void wrongTypesForSecretsOrImportsAreIgnored() {
        assertEquals(map(), Json.readSecrets("{\"secrets\":{\"secretKey\":\"A\"},\"imports\":\"x\"}"));
        assertEquals(map("A", "1"), Json.readSecrets("{\"secrets\":[1,\"x\",null,[],{\"secretKey\":\"A\",\"secretValue\":\"1\"}]}"));
    }

    @Test
    void readSecretsRejectsInvalidJson() {
        assertThrows(JsonParseException.class, () -> Json.readSecrets("{\"secrets\":[}"));
        assertThrows(JsonParseException.class, () -> Json.readSecrets("[]"));
    }

    @Test
    void readSecretsResultIsUnmodifiable() {
        Map<String, String> m = Json.readSecrets("{}");
        assertThrows(UnsupportedOperationException.class, () -> m.put("c", "d"));
    }

    // ---- writeObject ----

    @Test
    void writesAnObjectOfStringFieldsInOrder() {
        assertEquals("{\"clientId\":\"id\",\"clientSecret\":\"secret\"}",
                Json.writeObject(map("clientId", "id", "clientSecret", "secret")));
        assertEquals("{}", Json.writeObject(map()));
    }

    @Test
    void escapesWhatJsonRequires() {
        assertEquals("{\"k\":\"\\\" \\\\ \\b \\f \\n \\r \\t \\u0001 \\u001f\"}",
                Json.writeObject(map("k", "\" \\ \b \f \n \r \t \u0001 \u001f")));
    }

    @Test
    void escapesLineSeparatorsAndLoneSurrogatesButKeepsOtherText() {
        assertEquals("{\"k\":\"\\u2028\\u2029 / \u00e9 \uD83D\uDE00 \\ud800 \\udc00\"}",
                Json.writeObject(map("k", "\u2028\u2029 / \u00e9 \uD83D\uDE00 \uD800 \uDC00")));
    }

    private static Map<String, String> map(String... keyValues) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    private static String repeat(String s, int times) {
        StringBuilder b = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            b.append(s);
        }
        return b.toString();
    }
}
