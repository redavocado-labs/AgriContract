package com.agricontract.contract.infrastructure.messaging;

import com.agricontract.contract.application.exception.InvalidEventPayloadException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two hashes of golden-flow-events.yaml inspectionReportConfirmed (x-hash-contract), recomputed from the payload as
 * received:
 * <pre>
 * resultHash = SHA256(RFC8785(normalizedResult))
 * reportHash = SHA256(RFC8785({reportId, contractId, milestoneId, tier, reportFileHash, resultHash,
 *                              sourceTimestamp: confirmedAt, actorOrSourceIdentity: inspectorId}))
 * </pre>
 * The strings are hashed exactly as received (not lower-cased or reformatted). Only LEVEL_1_5 composes
 * actorOrSourceIdentity; LEVEL_2 is not handled yet.
 */
final class InspectionReportHashes {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private InspectionReportHashes() {
    }

    static String resultHash(Map<String, Object> normalizedResult) {
        return sha256(canonical(normalizedResult));
    }

    static String reportHash(String reportId, String contractId, String milestoneId, String tier,
                             String reportFileHash, String resultHash, String confirmedAt, String inspectorId) {
        Map<String, Object> commitment = new LinkedHashMap<>();
        commitment.put("reportId", reportId);
        commitment.put("contractId", contractId);
        commitment.put("milestoneId", milestoneId);
        commitment.put("tier", tier);
        commitment.put("reportFileHash", reportFileHash);
        commitment.put("resultHash", resultHash);
        commitment.put("sourceTimestamp", confirmedAt);
        commitment.put("actorOrSourceIdentity", inspectorId);
        return sha256(canonical(commitment));
    }

    /** Throws InvalidEventPayloadException (no value in the message) when either hash does not match. */
    static void verify(Map<String, Object> normalizedResult, String reportId, String contractId, String milestoneId,
                       String tier, String reportFileHash, String resultHash, String reportHash, String confirmedAt,
                       String inspectorId) {
        if (!resultHash(normalizedResult).equals(resultHash)) {
            throw new InvalidEventPayloadException("resultHash does not match normalizedResult", null);
        }
        if (!reportHash(reportId, contractId, milestoneId, tier, reportFileHash, resultHash, confirmedAt, inspectorId)
                .equals(reportHash)) {
            throw new InvalidEventPayloadException("reportHash does not match the report commitment", null);
        }
    }

    private static byte[] canonical(Map<String, Object> value) {
        try {
            return new JsonCanonicalizer(MAPPER.writeValueAsString(value)).getEncodedUTF8();
        } catch (JsonProcessingException e) {
            throw new InvalidEventPayloadException("Hash material is not serializable", e);
        } catch (IOException e) {
            throw new InvalidEventPayloadException("Hash material cannot be canonicalized", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
