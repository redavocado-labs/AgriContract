package com.agricontract.audit.domain.model;

import com.agricontract.audit.domain.exception.InvalidAuditRecordException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON for hashing: object keys sorted ascending (String.compareTo), no whitespace, nulls kept, arrays keep
 * their order, non-ASCII written raw. The tree only has String, null, List and Map nodes; there are no number or
 * boolean leaves (numbers travel as decimal text). Pure Java on purpose, so the bytes never depend on a JSON library.
 */
public final class CanonicalJson {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private CanonicalJson() {
    }

    /** ISO-8601 UTC with exactly 3 fraction digits and Z. */
    public static String timestamp(Instant instant) {
        return TIMESTAMP.format(instant);
    }

    public static String serialize(Object node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /** Validates the tree shape and returns an immutable deep copy. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeObject(Map<?, ?> map) {
        return (Map<String, Object>) normalize(map, "content");
    }

    private static Object normalize(Object node, String path) {
        if (node == null || node instanceof String) {
            return node;
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new InvalidAuditRecordException(path + " has a non-text key");
                }
                copy.put(key, normalize(entry.getValue(), path + "." + key));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                copy.add(normalize(list.get(i), path + "[" + i + "]"));
            }
            return Collections.unmodifiableList(copy);
        }
        throw new InvalidAuditRecordException(path + " must be text, null, a list or an object, not "
                + node.getClass().getSimpleName());
    }

    private static void write(Object node, StringBuilder out) {
        if (node == null) {
            out.append("null");
        } else if (node instanceof String text) {
            writeString(text, out);
        } else if (node instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put((String) entry.getKey(), entry.getValue());
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (node instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(list.get(i), out);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported node " + node.getClass().getName());
        }
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
