package com.guiltypotato.checkengine.core.toml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, forgiving TOML reader. Covers what mods.toml files actually use:
 * tables, array tables, quoted keys, strings (all 4 kinds), numbers, booleans, arrays, inline tables.
 * Keeps core dependency-free so the CLI stays a single plain jar.
 */
public final class Toml {
    private final String src;
    private int pos;

    private Toml(String src) {
        this.src = src;
    }

    /** Parses TOML text into nested maps. Tables are {@code Map<String,Object>}, array tables are {@code List<Map>}. */
    public static Map<String, Object> parse(String text) {
        Toml t = new Toml(text.startsWith("﻿") ? text.substring(1) : text);
        return t.document();
    }

    private Map<String, Object> document() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        while (true) {
            skipBlankAndComments();
            if (pos >= src.length()) return root;
            char c = src.charAt(pos);
            if (c == '[') {
                boolean array = src.startsWith("[[", pos);
                pos += array ? 2 : 1;
                List<String> path = keyPath();
                skipInlineSpace();
                expect(array ? "]]" : "]");
                current = array ? appendArrayTable(root, path) : table(root, path);
            } else {
                List<String> path = keyPath();
                skipInlineSpace();
                expect("=");
                skipInlineSpace();
                Object value = value();
                Map<String, Object> target = current;
                for (int i = 0; i < path.size() - 1; i++) target = child(target, path.get(i));
                target.put(path.get(path.size() - 1), value);
            }
            skipToLineEnd();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        if (existing instanceof Map) return (Map<String, Object>) existing;
        if (existing instanceof List<?> list && !list.isEmpty() && list.get(list.size() - 1) instanceof Map) {
            return (Map<String, Object>) list.get(list.size() - 1);
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    private static Map<String, Object> table(Map<String, Object> root, List<String> path) {
        Map<String, Object> t = root;
        for (String key : path) t = child(t, key);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> appendArrayTable(Map<String, Object> root, List<String> path) {
        Map<String, Object> parent = root;
        for (int i = 0; i < path.size() - 1; i++) parent = child(parent, path.get(i));
        String last = path.get(path.size() - 1);
        Object existing = parent.get(last);
        List<Object> list;
        if (existing instanceof List) {
            list = (List<Object>) existing;
        } else {
            list = new ArrayList<>();
            parent.put(last, list);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        list.add(entry);
        return entry;
    }

    private List<String> keyPath() {
        List<String> parts = new ArrayList<>();
        while (true) {
            skipInlineSpace();
            parts.add(key());
            skipInlineSpace();
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
            } else {
                return parts;
            }
        }
    }

    private String key() {
        char c = peek();
        if (c == '"') return basicString();
        if (c == '\'') return literalString();
        int start = pos;
        while (pos < src.length()) {
            char k = src.charAt(pos);
            if (Character.isLetterOrDigit(k) || k == '_' || k == '-') pos++;
            else break;
        }
        if (start == pos) throw error("expected a key");
        return src.substring(start, pos);
    }

    private Object value() {
        char c = peek();
        if (src.startsWith("\"\"\"", pos)) return multiBasicString();
        if (src.startsWith("'''", pos)) return multiLiteralString();
        if (c == '"') return basicString();
        if (c == '\'') return literalString();
        if (c == '[') return array();
        if (c == '{') return inlineTable();
        int start = pos;
        while (pos < src.length()) {
            char k = src.charAt(pos);
            if (k == ',' || k == ']' || k == '}' || k == '#' || k == '\n' || k == '\r') break;
            pos++;
        }
        String raw = src.substring(start, pos).trim();
        if (raw.isEmpty()) throw error("expected a value");
        if (raw.equals("true")) return Boolean.TRUE;
        if (raw.equals("false")) return Boolean.FALSE;
        String num = raw.replace("_", "");
        try {
            return Long.parseLong(num);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException ignored) {
            return raw; // dates and anything exotic: keep as text
        }
    }

    private List<Object> array() {
        expect("[");
        List<Object> list = new ArrayList<>();
        while (true) {
            skipBlankAndComments();
            if (peek() == ']') {
                pos++;
                return list;
            }
            list.add(value());
            skipBlankAndComments();
            if (peek() == ',') pos++;
        }
    }

    private Map<String, Object> inlineTable() {
        expect("{");
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            skipBlankAndComments();
            if (peek() == '}') {
                pos++;
                return map;
            }
            List<String> path = keyPath();
            skipInlineSpace();
            expect("=");
            skipInlineSpace();
            Object v = value();
            Map<String, Object> target = map;
            for (int i = 0; i < path.size() - 1; i++) target = child(target, path.get(i));
            target.put(path.get(path.size() - 1), v);
            skipBlankAndComments();
            if (peek() == ',') pos++;
        }
    }

    private String basicString() {
        expect("\"");
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw error("unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            // Strict TOML forbids this, but Forge/NeoForge's reader accepts it and real mods rely on it
            // (Dynamic Surroundings puts its credits list in one "..." string), so keep the newline.
            if (c == '\\') escape(sb);
            else sb.append(c);
        }
    }

    private String multiBasicString() {
        expect("\"\"\"");
        if (src.startsWith("\r\n", pos)) pos += 2;
        else if (peek() == '\n') pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw error("unterminated string");
            if (src.startsWith("\"\"\"", pos)) {
                pos += 3;
                while (peek() == '"') { // up to 2 quotes may end the content
                    sb.append('"');
                    pos++;
                }
                return sb.toString();
            }
            char c = src.charAt(pos++);
            if (c == '\\') {
                int save = pos;
                while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) pos++;
                if (pos < src.length() && (src.charAt(pos) == '\n' || src.charAt(pos) == '\r')) {
                    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
                } else {
                    pos = save;
                    escape(sb);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private void escape(StringBuilder sb) {
        if (pos >= src.length()) throw error("bad escape");
        char e = src.charAt(pos++);
        switch (e) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case '"' -> sb.append('"');
            case '\\' -> sb.append('\\');
            case 'u', 'U' -> {
                int len = e == 'u' ? 4 : 8;
                if (pos + len > src.length()) throw error("bad unicode escape");
                sb.appendCodePoint(Integer.parseInt(src.substring(pos, pos + len), 16));
                pos += len;
            }
            default -> sb.append('\\').append(e); // be forgiving
        }
    }

    private String literalString() {
        expect("'");
        int end = src.indexOf('\'', pos);
        if (end < 0) throw error("unterminated string");
        String s = src.substring(pos, end);
        pos = end + 1;
        return s;
    }

    private String multiLiteralString() {
        expect("'''");
        if (src.startsWith("\r\n", pos)) pos += 2;
        else if (peek() == '\n') pos++;
        int end = src.indexOf("'''", pos);
        if (end < 0) throw error("unterminated string");
        while (end + 3 < src.length() && src.charAt(end + 3) == '\'') end++;
        String s = src.substring(pos, end);
        pos = end + 3;
        return s;
    }

    private void skipBlankAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '#') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else {
                return;
            }
        }
    }

    private void skipInlineSpace() {
        while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) pos++;
    }

    /** After a statement: allow trailing spaces and a comment, then the line must end. */
    private void skipToLineEnd() {
        skipInlineSpace();
        if (pos < src.length() && src.charAt(pos) == '#') {
            while (pos < src.length() && src.charAt(pos) != '\n') pos++;
        }
        if (pos < src.length() && src.charAt(pos) != '\n' && src.charAt(pos) != '\r') {
            throw error("unexpected text after value");
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void expect(String s) {
        if (!src.startsWith(s, pos)) throw error("expected '" + s + "'");
        pos += s.length();
    }

    private TomlException error(String msg) {
        int line = 1;
        for (int i = 0; i < Math.min(pos, src.length()); i++) if (src.charAt(i) == '\n') line++;
        return new TomlException(msg + " on line " + line);
    }

    public static final class TomlException extends RuntimeException {
        public TomlException(String message) {
            super(message);
        }
    }
}
