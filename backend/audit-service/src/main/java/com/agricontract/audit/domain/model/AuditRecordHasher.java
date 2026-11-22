package com.agricontract.audit.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Record hash: lowercase hex SHA-256 of the canonical JSON of every typed field plus both prev pointers. */
public final class AuditRecordHasher {

    private AuditRecordHasher() {
    }

    public static String canonical(AuditRecord r, String prevHashGlobal, String prevHashSubject) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("recordId", r.recordId());
        node.put("subjectType", r.subjectType().name());
        node.put("subjectId", r.subjectId());
        node.put("sourceType", r.sourceType());
        node.put("sourceEventType", r.sourceEventType());
        node.put("sourceEventId", r.sourceEventId());
        node.put("causationId", r.causationId());
        node.put("correlationId", r.correlationId());
        node.put("sourceHash", r.sourceHash());
        node.put("occurredAt", CanonicalJson.timestamp(r.occurredAt()));
        node.put("content", r.content());
        node.put("prevHashGlobal", prevHashGlobal);
        node.put("prevHashSubject", prevHashSubject);
        node.put("createdAt", CanonicalJson.timestamp(r.createdAt()));
        return CanonicalJson.serialize(node);
    }

    public static String hash(AuditRecord r, String prevHashGlobal, String prevHashSubject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(r, prevHashGlobal, prevHashSubject).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
