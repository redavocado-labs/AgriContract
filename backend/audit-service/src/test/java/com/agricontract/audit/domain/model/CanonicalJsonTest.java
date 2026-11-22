package com.agricontract.audit.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalJsonTest {

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void keysAreSortedRecursivelyWithoutWhitespace() {
        Map<String, Object> tree = map("b", map("z", "1", "a", "2"), "a", "x", "B", "y");
        assertThat(CanonicalJson.serialize(tree)).isEqualTo("{\"B\":\"y\",\"a\":\"x\",\"b\":{\"a\":\"2\",\"z\":\"1\"}}");
    }

    @Test
    void nullKeepsItsKey() {
        assertThat(CanonicalJson.serialize(map("a", null, "b", "x"))).isEqualTo("{\"a\":null,\"b\":\"x\"}");
    }

    @Test
    void arraysKeepOrderAndObjectsInsideAreSorted() {
        Map<String, Object> tree = map("l", List.of("c", "a", map("y", "1", "x", "2")));
        assertThat(CanonicalJson.serialize(tree)).isEqualTo("{\"l\":[\"c\",\"a\",{\"x\":\"2\",\"y\":\"1\"}]}");
    }

    @Test
    void quoteBackslashAndControlCharactersAreEscaped() {
        assertThat(CanonicalJson.serialize(map("k", "a\"b\\c\nd\te\rf\bg\fh\u0001i\u001fj")))
                .isEqualTo("{\"k\":\"a\\\"b\\\\c\\nd\\te\\rf\\bg\\fh\\u0001i\\u001fj\"}");
    }

    @Test
    void nonAsciiIsKeptRaw() {
        assertThat(CanonicalJson.serialize(map("k", "Nông sản é 🌾")))
                .isEqualTo("{\"k\":\"Nông sản é 🌾\"}");
    }

    @Test
    void timestampHasExactlyThreeFractionDigitsAndZ() {
        assertThat(CanonicalJson.timestamp(Instant.parse("2026-11-20T08:00:01.123Z")))
                .isEqualTo("2026-11-20T08:00:01.123Z");
        assertThat(CanonicalJson.timestamp(Instant.parse("2026-11-20T08:00:01Z"))).isEqualTo("2026-11-20T08:00:01.000Z");
        assertThat(CanonicalJson.timestamp(Instant.parse("2026-11-20T08:00:01.120Z")))
                .isEqualTo("2026-11-20T08:00:01.120Z");
    }
}
