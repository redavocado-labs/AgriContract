package com.agricontract.contract.infrastructure.web.dto;

import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * BreachCaseResolutionRequest (golden-flow-api.yaml, D81): finalBreachingRole is required but may be null (no fault),
 * which a record cannot tell from a missing key, so the body is read as a tree. Unknown keys are refused.
 */
public record BreachCaseResolutionRequest(SignerRole finalBreachingRole, DecisionSource decisionSource,
                                          List<String> evidenceRefs) {
    private static final Set<String> KEYS = Set.of("finalBreachingRole", "decisionSource", "evidenceRefs");

    public static BreachCaseResolutionRequest parse(ObjectMapper mapper, String body) {
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
            if (!KEYS.contains(name)) {
                throw new IllegalArgumentException("Unknown field " + name);
            }
        });
        for (String required : KEYS) {
            if (!root.has(required)) {
                throw new IllegalArgumentException(required + " is required");
            }
        }
        JsonNode role = root.get("finalBreachingRole");
        SignerRole finalRole = null;
        if (!role.isNull()) {
            finalRole = enumValue(SignerRole.class, role, "finalBreachingRole");
        }
        JsonNode source = root.get("decisionSource");
        if (source.isNull()) {
            throw new IllegalArgumentException("decisionSource is required");
        }
        DecisionSource decisionSource = enumValue(DecisionSource.class, source, "decisionSource");
        if (decisionSource == DecisionSource.SYSTEM) {
            throw new IllegalArgumentException("decisionSource must be one of ARBITRATOR, INSPECTOR_L1_5, INSPECTOR_L2,"
                    + " MUTUAL");
        }
        JsonNode refs = root.get("evidenceRefs");
        if (!refs.isArray()) {
            throw new IllegalArgumentException("evidenceRefs must be an array of strings");
        }
        List<String> evidenceRefs = new ArrayList<>();
        for (JsonNode ref : refs) {
            if (!ref.isTextual()) {
                throw new IllegalArgumentException("evidenceRefs must be an array of strings");
            }
            evidenceRefs.add(ref.asText());
        }
        return new BreachCaseResolutionRequest(finalRole, decisionSource, evidenceRefs);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String field) {
        if (node.isTextual()) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(node.asText())) {
                    return constant;
                }
            }
        }
        throw new IllegalArgumentException(field + " is not one of the accepted values");
    }
}
