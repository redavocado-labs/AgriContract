package com.agricontract.contract.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #638, D101: the outbox publishes with mandatory=true, so an event type no queue is bound to comes back unroutable
 * and stalls every later contract-service event. Every event type this service writes must be a frozen event that
 * names at least one consumer.
 */
class OutboxEventCatalogTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Test
    void everyEventTypeWrittenToTheOutbox_isFrozen_andHasAConsumer() throws Exception {
        Map<String, List<String>> consumers = new HashMap<>();
        for (String file : List.of("events/golden-flow-events.yaml", "events/notification-commands.yaml")) {
            collect(YAML.readTree(Path.of(System.getProperty("contracts.directory"), file).toFile()), consumers);
        }

        List<String> written = eventTypeConstants();
        assertThat(written).contains("milestone.buyer_received", "inspection.level1_5_requested")
                .doesNotContain("milestone.flagged");
        for (String eventType : written) {
            assertThat(consumers).as("%s is not a frozen event", eventType).containsKey(eventType);
            assertThat(consumers.get(eventType)).as("%s has no consumer", eventType).isNotEmpty();
        }
    }

    /** The event type constants of the publisher: the String constants with a dot (WITHDRAW_OFFER is a reason code). */
    private static List<String> eventTypeConstants() throws IllegalAccessException {
        List<String> types = new ArrayList<>();
        for (Field field : OutboxContractEventPublisher.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                field.setAccessible(true);
                String value = (String) field.get(null);
                if (value.contains(".")) {
                    types.add(value);
                }
            }
        }
        return types;
    }

    /** Each node declaring x-consumers maps the eventType const found under it to those consumers. */
    private static void collect(JsonNode node, Map<String, List<String>> consumers) {
        if (node.isObject() && node.has("x-consumers")) {
            String eventType = eventTypeConst(node);
            if (eventType != null) {
                List<String> names = new ArrayList<>();
                node.get("x-consumers").forEach(n -> names.add(n.asText()));
                consumers.put(eventType, names);
            }
        }
        node.forEach(child -> collect(child, consumers));
    }

    private static String eventTypeConst(JsonNode node) {
        JsonNode constant = node.path("properties").path("eventType").path("const");
        if (constant.isTextual()) {
            return constant.asText();
        }
        for (JsonNode child : node) {
            String found = eventTypeConst(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
