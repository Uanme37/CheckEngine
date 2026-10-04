package com.guiltypotato.packdoctor.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON reader for jar-in-jar metadata, fabric.mod.json and CurseForge's minecraftinstance.json.
 * Objects become {@code Map<String,Object>}, arrays {@code List<Object>}, numbers {@code Double}.
 * Tolerates comments and trailing commas (some fabric.mod.json files have them).
 */
public final class Json {
    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json j = new Json(text.startsWith("﻿") ? text.substring(1) : text);
        j.skip();
        Object v = j.value();
        j.skip();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (v instanceof Map) return (Map<String, Object>) v;
        throw new JsonException("expected a JSON object");
    }

    private Object value() {
        skip();
        if (pos >= src.length()) throw error("unexpected end");
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            default:
                if (src.startsWith("true", pos)) {
                    pos += 4;
                    return Boolean.TRUE;
                }
                if (src.startsWith("false", pos)) {
                    pos += 5;
                    return Boolean.FALSE;
                }
                if (src.startsWith("null", pos)) {
                    pos += 4;
                    return null;
                }
                return number();
        }
    }

    private Map<String, Object> object() {
        pos++;
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            skip();
            if (peek() == '}') {
                pos++;
                return map;
            }
            String key = string();
            skip();
            if (peek() != ':') throw error("expected ':'");
            pos++;
            map.put(key, value());
            skip();
            if (peek() == ',') pos++;
            else if (peek() != '}') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        pos++;
        List<Object> list = new ArrayList<>();
        while (true) {
            skip();
            if (peek() == ']') {
                pos++;
                return list;
            }
            list.add(value());
            skip();
            if (peek() == ',') pos++;
            else if (peek() != ']') throw error("expected ',' or ']'");
        }
    }

    private String string() {
        if (peek() != '"') throw error("expected a string");
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) throw error("unterminated string");
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = src.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Double number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) pos++;
        if (start == pos) throw error("unexpected character '" + src.charAt(pos) + "'");
        return Double.parseDouble(src.substring(start, pos));
    }

    private void skip() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (src.startsWith("//", pos)) {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else if (src.startsWith("/*", pos)) {
                int end = src.indexOf("*/", pos + 2);
                pos = end < 0 ? src.length() : end + 2;
            } else {
                return;
            }
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private JsonException error(String msg) {
        return new JsonException(msg + " at offset " + pos);
    }

    public static final class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}
