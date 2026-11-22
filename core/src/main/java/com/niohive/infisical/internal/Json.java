package com.niohive.infisical.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The little JSON the library needs, without a JSON library: a strict RFC 8259 reader that keeps only the
 * fields asked for (it never builds a tree), and a writer for objects of string fields.
 */
public final class Json {

    static final int MAX_DEPTH = 64;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Json() {
    }

    /**
     * Scalar fields of a top-level object, as text. A {@code null}, object or array value means the key is
     * absent. When a key appears twice, the last one wins.
     *
     * @throws JsonParseException when {@code json} isn't a valid JSON object
     */
    public static Map<String, String> readTopLevel(String json) {
        Parser p = new Parser(json);
        Map<String, String> fields = new LinkedHashMap<>();
        p.readObject(key -> {
            String value = p.readScalar();
            if (value == null) {
                fields.remove(key);
            } else {
                fields.put(key, value);
            }
        });
        p.end();
        return Collections.unmodifiableMap(fields);
    }

    /**
     * An Infisical list-secrets response as secretKey to secretValue. Imported secrets come first, in array
     * order, so a later import overwrites an earlier one; then the folder's own secrets overwrite them all.
     * Entries with a blank key are skipped, and a missing or non-scalar value becomes "".
     *
     * @throws JsonParseException when {@code json} isn't a valid JSON object
     */
    public static Map<String, String> readSecrets(String json) {
        Parser p = new Parser(json);
        List<String[]> folder = new ArrayList<>();
        List<List<String[]>> imports = new ArrayList<>();
        p.readObject(key -> {
            if ("secrets".equals(key)) {
                folder.clear();
                folder.addAll(p.readSecretList());
            } else if ("imports".equals(key)) {
                imports.clear();
                imports.addAll(p.readImports());
            } else {
                p.skipValue();
            }
        });
        p.end();

        Map<String, String> secrets = new LinkedHashMap<>();
        for (List<String[]> imported : imports) {
            putAll(imported, secrets);
        }
        putAll(folder, secrets);
        return Collections.unmodifiableMap(secrets);
    }

    /** A JSON object of string fields, in the map's order. */
    public static String writeObject(Map<String, String> fields) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            writeString(b, field.getKey());
            b.append(':');
            writeString(b, field.getValue());
        }
        return b.append('}').toString();
    }

    private static void putAll(List<String[]> entries, Map<String, String> into) {
        for (String[] entry : entries) {
            into.put(entry[0], entry[1]);
        }
    }

    private static void writeString(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        b.append(c).append(s.charAt(++i));
                    } else if (c < 0x20 || c == ' ' || c == ' ' || Character.isSurrogate(c)) {
                        // Lone surrogates are escaped because UTF-8 encoding would silently turn them into '?'.
                        b.append("\\u").append(HEX[c >> 12 & 0xF]).append(HEX[c >> 8 & 0xF])
                                .append(HEX[c >> 4 & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        b.append(c);
                    }
                    break;
            }
        }
        b.append('"');
    }

    private interface FieldReader {
        void read(String key);
    }

    private static final class Parser {

        private final String s;
        private int pos;
        private int depth;

        Parser(String json) {
            this.s = json;
            if (s.startsWith("﻿")) {
                pos = 1;
            }
            skipWhitespace();
        }

        void end() {
            skipWhitespace();
            if (pos != s.length()) {
                throw error("end of input");
            }
        }

        void readObject(FieldReader fields) {
            expect('{');
            enter();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
            } else {
                while (true) {
                    skipWhitespace();
                    String key = readString();
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    fields.read(key);
                    skipWhitespace();
                    char c = next();
                    if (c == '}') {
                        break;
                    }
                    if (c != ',') {
                        throw error("',' or '}'");
                    }
                }
            }
            depth--;
        }

        void readArray(Runnable element) {
            expect('[');
            enter();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
            } else {
                while (true) {
                    skipWhitespace();
                    element.run();
                    skipWhitespace();
                    char c = next();
                    if (c == ']') {
                        break;
                    }
                    if (c != ',') {
                        throw error("',' or ']'");
                    }
                }
            }
            depth--;
        }

        /** The value's text; null for null, and for an object or array (which is skipped). */
        String readScalar() {
            char c = peek();
            switch (c) {
                case '{':
                    readObject(key -> skipValue());
                    return null;
                case '[':
                    readArray(this::skipValue);
                    return null;
                case '"':
                    return readString();
                case 't':
                    return literal("true");
                case 'f':
                    return literal("false");
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || isDigit(c)) {
                        return readNumber();
                    }
                    throw error("a value");
            }
        }

        void skipValue() {
            readScalar();
        }

        List<String[]> readSecretList() {
            List<String[]> secrets = new ArrayList<>();
            if (peek() != '[') {
                skipValue();
                return secrets;
            }
            readArray(() -> {
                if (peek() == '{') {
                    String[] secret = readSecret();
                    if (secret != null) {
                        secrets.add(secret);
                    }
                } else {
                    skipValue();
                }
            });
            return secrets;
        }

        List<List<String[]>> readImports() {
            List<List<String[]>> imports = new ArrayList<>();
            if (peek() != '[') {
                skipValue();
                return imports;
            }
            readArray(() -> {
                if (peek() != '{') {
                    skipValue();
                    return;
                }
                List<String[]> secrets = new ArrayList<>();
                readObject(key -> {
                    if ("secrets".equals(key)) {
                        secrets.clear();
                        secrets.addAll(readSecretList());
                    } else {
                        skipValue();
                    }
                });
                imports.add(secrets);
            });
            return imports;
        }

        private String[] readSecret() {
            String[] keyAndValue = new String[2];
            readObject(key -> {
                if ("secretKey".equals(key)) {
                    keyAndValue[0] = readScalar();
                } else if ("secretValue".equals(key)) {
                    keyAndValue[1] = readScalar();
                } else {
                    skipValue();
                }
            });
            if (Strings.isBlank(keyAndValue[0])) {
                return null;
            }
            return new String[] {keyAndValue[0], keyAndValue[1] == null ? "" : keyAndValue[1]};
        }

        private String readString() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("end of string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    b.append(readEscape());
                } else if (c < 0x20) {
                    throw error("an escaped control character");
                } else {
                    b.append(c);
                }
            }
        }

        private char readEscape() {
            char c = next();
            switch (c) {
                case '"':
                    return '"';
                case '\\':
                    return '\\';
                case '/':
                    return '/';
                case 'b':
                    return '\b';
                case 'f':
                    return '\f';
                case 'n':
                    return '\n';
                case 'r':
                    return '\r';
                case 't':
                    return '\t';
                case 'u':
                    int value = 0;
                    for (int i = 0; i < 4; i++) {
                        value = value << 4 | hexValue(next());
                    }
                    return (char) value;
                default:
                    throw error("a valid escape");
            }
        }

        private int hexValue(char c) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            throw error("a hex digit");
        }

        private String readNumber() {
            int start = pos;
            if (at('-')) {
                pos++;
            }
            if (at('0')) {
                pos++;
            } else if (pos < s.length() && s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                skipDigits();
            } else {
                throw error("a digit");
            }
            if (at('.')) {
                pos++;
                requireDigits();
            }
            if (at('e') || at('E')) {
                pos++;
                if (at('+') || at('-')) {
                    pos++;
                }
                requireDigits();
            }
            return s.substring(start, pos);
        }

        private void requireDigits() {
            if (pos >= s.length() || !isDigit(s.charAt(pos))) {
                throw error("a digit");
            }
            skipDigits();
        }

        private void skipDigits() {
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
        }

        private String literal(String word) {
            if (!s.startsWith(word, pos)) {
                throw error(word);
            }
            pos += word.length();
            return word;
        }

        private void enter() {
            if (++depth > MAX_DEPTH) {
                throw error("nesting depth of at most " + MAX_DEPTH);
            }
        }

        private void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private boolean at(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private char peek() {
            if (pos >= s.length()) {
                throw error("more input");
            }
            return s.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) {
                pos--;
                throw error("'" + c + "'");
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private JsonParseException error(String expected) {
            return new JsonParseException("Invalid JSON at position " + pos + ": expected " + expected);
        }
    }
}
