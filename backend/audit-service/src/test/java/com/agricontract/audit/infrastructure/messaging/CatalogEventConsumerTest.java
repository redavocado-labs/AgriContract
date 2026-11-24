package com.agricontract.audit.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.audit.application.dto.AuditIngestCommand;
import com.agricontract.audit.domain.model.CanonicalJson;
import com.agricontract.audit.domain.model.SubjectType;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** #255: every catalog event read as golden-flow-events.yaml freezes it. */
class CatalogEventConsumerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    private final CatalogEventConsumer consumer = new CatalogEventConsumer(new EventEnvelopeCodec(), command -> { });

    @Test
    @SuppressWarnings("unchecked")
    void theCatalog_matchesTheFrozenSchema_requiredAndKnownKeys() throws Exception {
        Map<String, Object> root = new Yaml().load(Files.readString(
                Path.of("../../contracts/events/golden-flow-events.yaml")));
        Map<String, Map<String, Object>> defs = (Map<String, Map<String, Object>>) root.get("$defs");
        for (CatalogEvent event : CatalogEvent.ALL) {
            Map<String, Object> payload = null;
            String producer = null;
            for (Map<String, Object> def : defs.values()) {
                for (Object part : (List<Object>) def.getOrDefault("allOf", List.of())) {
                    Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) part)
                            .getOrDefault("properties", Map.of());
                    Object type = props.get("eventType");
                    if (type instanceof Map<?, ?> t && event.eventType().equals(t.get("const"))) {
                        payload = (Map<String, Object>) props.get("payload");
                        producer = (String) ((Map<String, Object>) props.get("producer")).get("const");
                    }
                }
            }
            assertThat(payload).as(event.eventType()).isNotNull();
            assertThat(event.required()).as(event.eventType())
                    .containsExactlyInAnyOrderElementsOf((List<String>) payload.get("required"));
            List<String> known = new ArrayList<>(event.required());
            known.addAll(event.optional());
            assertThat(known).as(event.eventType())
                    .containsExactlyInAnyOrderElementsOf(((Map<String, Object>) payload.get("properties")).keySet());
            assertThat(event.exchange()).as(event.eventType()).isEqualTo(switch (producer) {
                case "contract-service" -> CatalogEvent.CONTRACTS;
                case "inspection-service" -> CatalogEvent.INSPECTION;
                case "bank-service" -> CatalogEvent.BANK;
                case "reputation-service" -> CatalogEvent.REPUTATION;
                default -> throw new AssertionError(producer);
            });
        }
        assertThat(CatalogEvent.ALL).extracting(CatalogEvent::eventType).doesNotHaveDuplicates()
                .doesNotContain("contract.signed").hasSize(18);
    }

    @Test
    void everyEvent_becomesARecordOfItsSubject_andTheContentIsACanonicalTree() throws Exception {
        for (Map.Entry<String, Map<String, Object>> sample : samples().entrySet()) {
            AuditIngestCommand command = consumer.toCommand(body(sample.getKey(), sample.getValue()));
            assertThat(command.sourceEventType()).isEqualTo(sample.getKey());
            assertThat(CanonicalJson.serialize(CanonicalJson.normalizeObject(command.content()))).isNotBlank();
        }
    }

    @Test
    void sourceTypeSubjectAndSourceHash_perEvent() throws Exception {
        Map<String, Map<String, Object>> samples = samples();
        AuditIngestCommand weighed = command("milestone.seller_weighed", samples);
        assertThat(weighed.sourceType()).isEqualTo("MILESTONE_EVENT");
        assertThat(weighed.subjectType()).isEqualTo(SubjectType.CONTRACT);
        assertThat(weighed.subjectId()).isEqualTo(samples.get("milestone.seller_weighed").get("contractId"));
        assertThat(weighed.content().get("sellerDeclaredWeight")).as("exact decimal text").isEqualTo("10000.500");
        assertThat(weighed.sourceHash()).isNull();

        AuditIngestCommand settled = command("milestone.settled", samples);
        assertThat(settled.content()).as("contact data is never chained").doesNotContainKey("recipients")
                .containsEntry("lockedAmount", "150000000.00");

        AuditIngestCommand l15 = command("inspection.report_confirmed", samples);
        assertThat(l15.sourceType()).isEqualTo("INSPECTION_REPORT");
        assertThat(l15.sourceHash()).isEqualTo(HASH_C);
        samples.get("inspection.report_confirmed").put("tier", "LEVEL_2");
        assertThat(command("inspection.report_confirmed", samples).sourceType())
                .isEqualTo("EXTERNAL_INSPECTION_REPORT");

        AuditIngestCommand remedy = command("remedy.finalized", samples);
        assertThat(remedy.sourceType()).isEqualTo("REMEDY_FINALIZED");
        assertThat(remedy.content().get("penaltyEligible")).isEqualTo("false");
        assertThat(remedy.content().get("breachCaseId")).isNull();
        assertThat(remedy.content().get("remedyLegs")).isInstanceOf(List.class);

        assertThat(command("contract.settled", samples).sourceType()).isEqualTo("CONTRACT_SETTLED");
        assertThat(command("contract.document_generated", samples).sourceHash()).isEqualTo(HASH_A);

        AuditIngestCommand lock = command("bank.security_lock_changed", samples);
        assertThat(lock.sourceType()).isEqualTo("SECURITY_LOCK_TRIGGERED");
        assertThat(lock.subjectType()).isEqualTo(SubjectType.SYSTEM);
        assertThat(lock.subjectId()).isEqualTo("SYSTEM");
        samples.get("bank.security_lock_changed").put("action", "UNLOCKED");
        assertThat(command("bank.security_lock_changed", samples).sourceType()).isEqualTo("SECURITY_UNLOCK_TRIGGERED");

        AuditIngestCommand str = command("bank.suspicious_report_created", samples);
        Map<String, Object> strPayload = samples.get("bank.suspicious_report_created");
        assertThat(str.subjectType()).isEqualTo(SubjectType.USER_PAIR);
        assertThat(str.subjectId()).isEqualTo(strPayload.get("buyerId") + ":" + strPayload.get("sellerId"));
        assertThat(str.sourceType()).isEqualTo("STRUCTURING_REPORT");

        assertThat(command("reputation.elevated_risk_cleared", samples).sourceType()).isEqualTo("AML_RISK_CLEARED");
        assertThat(command("bank.verifier_key_registered", samples).sourceType())
                .isEqualTo("EXTERNAL_VERIFIER_KEY_REGISTERED");
        assertThat(command("inspection.level2_commissioned", samples).sourceType())
                .isEqualTo("LEVEL2_INSPECTION_COMMISSIONED");
        assertThat(command("breach.reported", samples).sourceType()).isEqualTo("BREACH_REPORTED");
        assertThat(command("contract.terminated", samples).sourceType()).isEqualTo("CONTRACT_TERMINATED");
    }

    @Test
    void aPayloadOutsideTheFrozenShape_isRefused() throws Exception {
        List<Runnable> bad = new ArrayList<>();
        bad.add(() -> mutate("milestone.seller_weighed", p -> p.remove("declarationNumber")));
        bad.add(() -> mutate("milestone.seller_weighed", p -> p.put("milestoneId", "not-a-uuid")));
        bad.add(() -> mutate("milestone.buyer_confirmed", p -> p.put("confirmedBy", null)));
        bad.add(() -> mutate("inspection.report_confirmed", p -> p.put("reportHash", "ABC")));
        bad.add(() -> mutate("inspection.report_confirmed", p -> p.put("tier", "LEVEL_3")));
        bad.add(() -> mutate("bank.security_lock_changed", p -> p.put("action", "PAUSED")));
        bad.add(() -> mutate("milestone.seller_weighed", p -> p.put("deliveryCertificateFileId", null)));
        for (Runnable r : bad) {
            assertThatThrownBy(r::run).isInstanceOf(InvalidEventPayloadException.class);
        }
        assertThatThrownBy(() -> consumer.toCommand(body("contract.withdrawn", Map.of("contractId", id()))))
                .isInstanceOf(InvalidEventPayloadException.class);
        assertThatThrownBy(() -> consumer.toCommand("{not json".getBytes()))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    void nullableKeys_mayBeNull_andTheEnvelopeSubjectMustBeTheContract() throws Exception {
        Map<String, Map<String, Object>> samples = samples();
        samples.get("breach.reported").put("milestoneId", null);
        assertThat(command("breach.reported", samples).content().get("milestoneId")).isNull();

        Map<String, Object> event = envelope("milestone.settled", samples.get("milestone.settled"));
        event.put("subject", Map.of("type", "CONTRACT", "id", id()));
        assertThatThrownBy(() -> consumer.toCommand(JSON.writeValueAsBytes(event)))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    private void mutate(String type, java.util.function.Consumer<Map<String, Object>> change) {
        try {
            Map<String, Object> payload = samples().get(type);
            change.accept(payload);
            consumer.toCommand(body(type, payload));
        } catch (InvalidEventPayloadException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AuditIngestCommand command(String type, Map<String, Map<String, Object>> samples) throws Exception {
        return consumer.toCommand(body(type, samples.get(type)));
    }

    /** One valid payload per catalog event, in the shapes golden-flow-events.yaml freezes. */
    static Map<String, Map<String, Object>> samples() {
        String contract = id();
        String milestone = id();
        Map<String, Map<String, Object>> s = new LinkedHashMap<>();
        s.put("milestone.seller_weighed", map("contractId", contract, "milestoneId", milestone, "declarationNumber", 1,
                "sellerDeclaredWeight", new BigDecimal("10000.500"), "sellerEvidenceFileId", id()));
        s.put("milestone.buyer_received", map("contractId", contract, "milestoneId", milestone,
                "buyerReceivedWeight", new BigDecimal("9800"), "buyerEvidenceFileId", id(),
                "deliveryCertificateFileId", id(), "receivedAt", "2026-11-24T03:00:00Z"));
        s.put("milestone.buyer_confirmed", map("contractId", contract, "milestoneId", milestone, "confirmedBy", "BUYER"));
        s.put("milestone.settled", map("contractId", contract, "milestoneId", milestone, "lockedAmount",
                "150000000.00", "actualAmount", "147000000.00", "recipients",
                List.of(map("role", "BUYER", "userId", id(), "email", "buyer@example.com"))));
        s.put("milestone.force_majeure_claimed", map("contractId", contract, "milestoneId", milestone,
                "forceMajeureClaimId", id()));
        s.put("milestone.force_majeure_resolved", map("contractId", contract, "milestoneId", milestone,
                "forceMajeureClaimId", id(), "decision", "APPROVE"));
        s.put("milestone.cancelled_with_penalty", map("contractId", contract, "milestoneId", milestone,
                "remedyDecisionId", id(), "finalBreachingRole", "SELLER", "breachReasonCode", "SHORTFALL",
                "penaltyAmount", "12000000.00"));
        s.put("breach.reported", map("breachCaseId", id(), "contractId", contract, "milestoneId", milestone,
                "requestedBy", "BUYER", "allegedBreachingRole", "SELLER", "breachReasonCode",
                "QUALITY_BELOW_COMMITTED", "severity", "MATERIAL"));
        s.put("remedy.finalized", map("remedyDecisionId", id(), "attributionDecisionId", id(), "breachCaseId", null,
                "contractId", contract, "buyerId", id(), "sellerId", id(), "affectedMilestoneIds", List.of(),
                "settledMilestoneCount", 2, "finalBreachingRole", null, "breachReasonCode", null,
                "qualityDisposition", null, "decisionSource", "SYSTEM", "penaltyEligible", false,
                "reputationEligible", false, "remedyLegs", List.of(map("remedyLegId", id(), "remedyType",
                        "PAYMENT_REFUND", "fundType", "BUYER_DEPOSIT", "role", "BUYER", "amount", "15000000.00"))));
        s.put("contract.terminated", map("contractId", contract, "terminationType", "WITHDRAW_OFFER", "requestedBy",
                "BUYER", "finalBreachingRole", null, "breachReasonCode", null, "remedyDecisionId", null,
                "breachCaseId", null, "affectedMilestoneIds", List.of(), "supersededByContractId", null,
                "replacesContractId", null));
        s.put("contract.settled", map("contractId", contract, "buyerId", id(), "sellerId", id()));
        s.put("contract.document_generated", map("documentId", id(), "contractId", contract, "documentType",
                "FINAL_SIGNED_CONTRACT", "fileId", id(), "sourceContentHash", HASH_A, "documentFileHash", HASH_B,
                "templateCode", "COFFEE_SALE", "templateVersion", "1.0", "generatedAt", "2026-11-24T03:00:00Z"));
        s.put("inspection.report_confirmed", map("reportId", id(), "contractId", contract, "milestoneId", milestone,
                "tier", "LEVEL_1_5", "normalizedResult", map("status", "COMPLETE", "acceptedQuantityKg", "9800.000"),
                "resultHash", HASH_A, "reportFileHash", HASH_B, "reportHash", HASH_C,
                "confirmedAt", "2026-11-24T03:00:00Z", "inspectorId", id()));
        s.put("inspection.level2_commissioned", map("contractId", contract, "commissionId", id(), "org", "SGS",
                "requestedAt", "2026-11-24T03:00:00Z"));
        s.put("bank.verifier_key_registered", map("publicKeyFingerprint", "SHA256:abc", "rotationSignedByOldKey", null));
        s.put("bank.security_lock_changed", map("action", "LOCKED", "verifierOrgId", "VICOFA", "reason", "hash mismatch",
                "signedPayload", map("nonce", "n-1", "signature", "sig")));
        s.put("bank.suspicious_report_created", map("buyerId", id(), "sellerId", id(),
                "windowEnd", "2026-11-24T00:00:00Z", "reportHash", HASH_A));
        s.put("reputation.elevated_risk_cleared", map("buyerId", id(), "sellerId", id(), "clearedByArbitratorId", id(),
                "reason", "verified trade pattern", "proposedByOperatorId", id(), "approvedByArbitratorId", id()));
        return s;
    }

    static byte[] body(String type, Map<String, Object> payload) throws Exception {
        return JSON.writeValueAsBytes(envelope(type, payload));
    }

    static Map<String, Object> envelope(String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", id());
        event.put("eventType", type);
        event.put("eventVersion", 1);
        event.put("occurredAt", "2026-11-24T03:00:00Z");
        event.put("producer", "contract-service");
        event.put("aggregateId", payload.getOrDefault("contractId", "SYSTEM"));
        event.put("correlationId", id());
        event.put("payload", payload);
        return event;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
