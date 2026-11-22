package com.agricontract.contract.infrastructure.web.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * BreachContestRequest and BreachContestEvidenceRequest (golden-flow-api.yaml, D91), read as a tree so that every
 * violation of the schema is one 400 with its own message: unknown keys, reason 1..1000 characters, at most 10
 * unique uuid file ids (contest: optional, evidence top-up: 1..10).
 */
public final class BreachContestRequest {
    static final int MAX_FILES = 10;
    static final int MAX_REASON = 1000;

    private BreachContestRequest() {
    }

    /** reason and the optional evidenceFileIds. */
    public record Contest(String reason, List<String> evidenceFileIds) {
        public static Contest parse(ObjectMapper mapper, String body) {
            JsonNode root = object(mapper, body, Set.of("reason", "evidenceFileIds"));
            JsonNode reason = root.get("reason");
            if (reason == null || !reason.isTextual() || reason.asText().isEmpty()
                    || reason.asText().length() > MAX_REASON) {
                throw new IllegalArgumentException("reason is required (1 to " + MAX_REASON + " characters)");
            }
            List<String> files = root.has("evidenceFileIds") ? readFileIds(root.get("evidenceFileIds"),
                    "evidenceFileIds", 0) : List.of();
            return new Contest(reason.asText(), files);
        }
    }

    /** fileIds, 1..10. */
    public record Evidence(List<String> fileIds) {
        public static Evidence parse(ObjectMapper mapper, String body) {
            JsonNode root = object(mapper, body, Set.of("fileIds"));
            if (!root.has("fileIds")) {
                throw new IllegalArgumentException("fileIds is required");
            }
            return new Evidence(readFileIds(root.get("fileIds"), "fileIds", 1));
        }
    }

    private static JsonNode object(ObjectMapper mapper, String body, Set<String> keys) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Body is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Body must be a JSON object");
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!keys.contains(name)) {
                throw new IllegalArgumentException("Unknown field " + name);
            }
        });
        return root;
    }

    private static List<String> readFileIds(JsonNode node, String field, int min) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " must be an array of file ids");
        }
        if (node.size() < min || node.size() > MAX_FILES) {
            throw new IllegalArgumentException(field + " must hold " + min + " to " + MAX_FILES + " file ids");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String id = item.isTextual() ? item.asText() : null;
            try {
                if (id == null) {
                    throw new IllegalArgumentException();
                }
                id = UUID.fromString(id).toString();
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(field + " must be an array of file ids (uuid)");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException(field + " must not repeat a file id");
            }
        }
        return new ArrayList<>(ids);
    }
}
