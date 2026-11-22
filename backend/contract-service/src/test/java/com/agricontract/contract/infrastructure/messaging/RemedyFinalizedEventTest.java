package com.agricontract.contract.infrastructure.messaging;

import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.goods;
import static com.agricontract.contract.domain.model.terms.CoffeeTermsFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agricontract.contract.domain.model.Contract;
import com.agricontract.contract.domain.model.breach.BreachReasonCode;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.breach.QualityDisposition;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.agricontract.contract.domain.model.milestone.ContestedBy;
import com.agricontract.contract.domain.model.milestone.FlagIssueType;
import com.agricontract.contract.domain.model.milestone.FundingStatus;
import com.agricontract.contract.domain.model.milestone.Milestone;
import com.agricontract.contract.domain.model.milestone.MilestoneFlag;
import com.agricontract.contract.domain.model.milestone.MilestoneStatus;
import com.agricontract.contract.domain.model.remedy.RemedyDecision;
import com.agricontract.contract.domain.model.remedy.RemedyFundType;
import com.agricontract.contract.domain.model.remedy.RemedyLeg;
import com.agricontract.contract.domain.model.remedy.RemedyStatus;
import com.agricontract.contract.domain.model.remedy.RemedyType;
import com.agricontract.contract.domain.model.terms.CanonicalTerms;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.infrastructure.contract.FrozenSchemas;
import com.agricontract.contract.infrastructure.terms.JcsTermsCanonicalizer;
import com.agricontract.eventing.EventEnvelope;
import com.agricontract.eventing.EventEnvelopeCodec;
import com.agricontract.eventing.outbox.OutboxWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** remedy.finalized and milestone.dispute_resolved as they go to the outbox (#137), against the frozen schemas. */
class RemedyFinalizedEventTest {
    private static final String BUYER = "0b7e3b7a-1c1e-4c5e-9f0a-3d2b1a0c9e8f";
    private static final String SELLER = "5a4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a";
    private static final Instant AT = Instant.parse("2026-11-23T03:00:00.123456Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final OutboxContractEventPublisher publisher = new OutboxContractEventPublisher(outbox, JSON);
    private final Contract contract = contract();

    // ---- every case of the YAML if/then rules ----

    @Test
    void noFaultPartiallyConforming_isValid() throws Exception {
        JsonNode event = publish(decision(RemedyStatus.FINALIZED, null, null, QualityDisposition.PARTIALLY_CONFORMING,
                leg(RemedyType.PAYMENT_SETTLEMENT, RemedyFundType.MILESTONE_PAYMENT, SignerRole.SELLER, "144060000.00"),
                leg(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT, SignerRole.BUYER, "5940000.00")));

        assertThat(event.at("/payload/penaltyEligible").asBoolean()).isFalse();
        assertThat(event.at("/payload/reputationEligible").asBoolean()).isFalse();
        assertThat(event.at("/payload/finalBreachingRole").isNull()).isTrue();
        assertThat(event.at("/payload/breachReasonCode").isNull()).isTrue();
        assertThat(event.at("/payload/qualityDisposition").asText()).isEqualTo("PARTIALLY_CONFORMING");
        assertThat(event.at("/payload/remedyLegs")).hasSize(2);
        assertThat(event.at("/payload/remedyLegs/0/amount").asText()).isEqualTo("144060000.00");
        assertThat(event.at("/payload/remedyLegs/0/role").asText()).isEqualTo("SELLER");
        assertThat(event.at("/payload/affectedMilestoneIds")).hasSize(1);
        assertThat(event.at("/payload/attributionDecisionId").asText())
                .isEqualTo(event.at("/payload/breachCaseId").asText());
        assertThat(event.at("/payload/buyerId").asText()).isEqualTo(BUYER);
        assertThat(event.at("/payload/sellerId").asText()).isEqualTo(SELLER);
        assertThat(event.at("/payload/decisionSource").asText()).isEqualTo("INSPECTOR_L1_5");
    }

    @Test
    void noFaultConforming_isValid() throws Exception {
        publish(decision(RemedyStatus.FINALIZED, null, null, QualityDisposition.CONFORMING,
                leg(RemedyType.PAYMENT_SETTLEMENT, RemedyFundType.MILESTONE_PAYMENT, SignerRole.SELLER, "150000000.00")));
    }

    @Test
    void quantityWithoutDisposition_isValid() throws Exception {
        JsonNode event = publish(decision(RemedyStatus.FINALIZED, null, null, null,
                leg(RemedyType.PAYMENT_SETTLEMENT, RemedyFundType.MILESTONE_PAYMENT, SignerRole.SELLER, "147000000.00"),
                leg(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT, SignerRole.BUYER, "3000000.00")));

        assertThat(event.at("/payload/qualityDisposition").isNull()).isTrue();
    }

    @Test
    void nonConforming_isValid_withRefundAndForfeiture() throws Exception {
        JsonNode event = publish(nonConforming());

        assertThat(event.at("/payload/finalBreachingRole").asText()).isEqualTo("SELLER");
        assertThat(event.at("/payload/breachReasonCode").asText()).isEqualTo("QUALITY_BELOW_COMMITTED");
        assertThat(event.at("/payload/penaltyEligible").asBoolean()).isTrue();
        assertThat(event.at("/payload/reputationEligible").asBoolean()).isTrue();
        assertThat(event.at("/payload/remedyLegs/1/remedyType").asText()).isEqualTo("DEPOSIT_FORFEITURE");
        assertThat(event.at("/payload/remedyLegs/1/fundType").asText()).isEqualTo("SELLER_DEPOSIT");
        assertThat(event.at("/payload/remedyLegs/1/role").asText()).isEqualTo("BUYER");
        assertThat(event.at("/payload/remedyLegs/1/amount").asText()).isEqualTo("12000000.00");
    }

    @Test
    void nonConforming_withNoForfeitureLeg_isValid() throws Exception {
        publish(decision(RemedyStatus.FINALIZED, SignerRole.SELLER, BreachReasonCode.QUALITY_BELOW_COMMITTED,
                QualityDisposition.NON_CONFORMING,
                leg(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT, SignerRole.BUYER, "150000000.00")));
    }

    // ---- the schema's if/then rules do reject what they should (the payload is built to be inside them) ----

    @Test
    void theSchemaRejectsEachBrokenRule() throws Exception {
        JsonNode good = envelopeJson(nonConforming());

        // NON_CONFORMING needs penaltyEligible and reputationEligible true.
        assertInvalid(mutate(good, "/payload", "penaltyEligible", false));
        assertInvalid(mutate(good, "/payload", "reputationEligible", false));
        // NON_CONFORMING needs the seller and the reason.
        assertInvalid(mutate(good, "/payload", "finalBreachingRole", null));
        assertInvalid(mutate(good, "/payload", "breachReasonCode", "NON_DELIVERY"));
        // No fault never carries a penalty leg or a forfeiture.
        JsonNode noFault = envelopeJson(decision(RemedyStatus.FINALIZED, null, null,
                QualityDisposition.PARTIALLY_CONFORMING,
                leg(RemedyType.PAYMENT_SETTLEMENT, RemedyFundType.MILESTONE_PAYMENT, SignerRole.SELLER, "150000000.00")));
        assertInvalid(mutate(noFault, "/payload", "penaltyEligible", true));
        assertInvalid(mutate(noFault, "/payload", "finalBreachingRole", "SELLER"));
        ObjectNode withForfeiture = noFault.deepCopy();
        ((ObjectNode) withForfeiture.at("/payload/remedyLegs/0")).put("remedyType", "DEPOSIT_FORFEITURE");
        assertInvalid(withForfeiture);
        // Money is "...00" and the payload is closed.
        assertInvalid(mutate(good, "/payload", "extra", "x"));
        ObjectNode badMoney = good.deepCopy();
        ((ObjectNode) badMoney.at("/payload/remedyLegs/0")).put("amount", "150000000");
        assertInvalid(badMoney);
    }

    // ---- envelope and refusals ----

    @Test
    void envelopeIsKeyedByTheDecision_andCorrelatedToTheContract() throws Exception {
        RemedyDecision d = nonConforming();

        publisher.remedyFinalized(contract, d, AT);

        EventEnvelope envelope = appended();
        assertThat(envelope.eventType()).isEqualTo("remedy.finalized");
        assertThat(envelope.aggregateId()).isEqualTo(d.remedyDecisionId());
        assertThat(envelope.correlationId()).isEqualTo(contract.getContractId().value());
        assertThat(envelope.occurredAt()).isEqualTo(AT);
        assertThat(envelope.eventId()).isNotNull();
    }

    @Test
    void aDecisionStillPendingTheContestWindow_orOfAnotherContract_isRefused() {
        RemedyDecision pending = decision(RemedyStatus.PENDING_CONTEST, SignerRole.SELLER,
                BreachReasonCode.QUALITY_BELOW_COMMITTED, QualityDisposition.NON_CONFORMING,
                leg(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT, SignerRole.BUYER, "150000000.00"));

        assertThatThrownBy(() -> publisher.remedyFinalized(contract, pending, AT))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- milestone.dispute_resolved ----

    @Test
    void disputeResolved_carriesTheGivenRole_andTheBuyerAsFlagger() throws Exception {
        for (SignerRole favors : SignerRole.values()) {
            OutboxWriter writer = mock(OutboxWriter.class);
            new OutboxContractEventPublisher(writer, JSON).milestoneDisputeResolved(contract, settledMilestone(),
                    favors);
            ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
            verify(writer).append(captor.capture());
            JsonNode json = JSON.readTree(new EventEnvelopeCodec().toJson(captor.getValue()));
            FrozenSchemas.assertEvent("milestoneDisputeResolved", json);
            assertThat(json.at("/payload/resolutionFavors").asText()).isEqualTo(favors.name());
            assertThat(json.at("/payload/flaggedByUserId").asText()).isEqualTo(BUYER);
            assertThat(json.at("/payload/flaggedBy").asText()).isEqualTo("BUYER");
        }
    }

    // ---- helpers ----

    private JsonNode publish(RemedyDecision d) throws Exception {
        JsonNode json = envelopeJson(d);
        FrozenSchemas.assertEvent("remedyFinalized", json);
        return json;
    }

    private JsonNode envelopeJson(RemedyDecision d) throws Exception {
        OutboxWriter writer = mock(OutboxWriter.class);
        new OutboxContractEventPublisher(writer, JSON).remedyFinalized(contract, d, AT);
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(writer).append(captor.capture());
        return JSON.readTree(new EventEnvelopeCodec().toJson(captor.getValue()));
    }

    private static void assertInvalid(JsonNode json) {
        assertThatThrownBy(() -> FrozenSchemas.assertEvent("remedyFinalized", json))
                .isInstanceOf(AssertionError.class);
    }

    private static JsonNode mutate(JsonNode json, String pointer, String field, Object value) {
        ObjectNode copy = json.deepCopy();
        ObjectNode target = (ObjectNode) copy.at(pointer);
        if (value == null) {
            target.putNull(field);
        } else if (value instanceof Boolean b) {
            target.put(field, b);
        } else {
            target.put(field, value.toString());
        }
        return copy;
    }

    private EventEnvelope appended() {
        ArgumentCaptor<EventEnvelope> captor = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(outbox).append(captor.capture());
        return captor.getValue();
    }

    private RemedyDecision nonConforming() {
        return decision(RemedyStatus.FINALIZED, SignerRole.SELLER, BreachReasonCode.QUALITY_BELOW_COMMITTED,
                QualityDisposition.NON_CONFORMING,
                leg(RemedyType.PAYMENT_REFUND, RemedyFundType.MILESTONE_PAYMENT, SignerRole.BUYER, "150000000.00"),
                leg(RemedyType.DEPOSIT_FORFEITURE, RemedyFundType.SELLER_DEPOSIT, SignerRole.BUYER, "12000000.00"));
    }

    private RemedyDecision decision(RemedyStatus status, SignerRole finalRole, BreachReasonCode reason,
                                    QualityDisposition disposition, RemedyLeg... legs) {
        return new RemedyDecision(UUID.randomUUID().toString(), contract.getContractId().value(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), status, finalRole, reason, disposition,
                DecisionSource.INSPECTOR_L1_5, new VndAmount(BigDecimal.ZERO), new VndAmount(BigDecimal.ZERO), 0,
                List.of(legs), AT, status == RemedyStatus.FINALIZED ? AT : null);
    }

    private static RemedyLeg leg(RemedyType type, RemedyFundType fund, SignerRole role, String amount) {
        return new RemedyLeg(UUID.randomUUID().toString(), type, fund, role, VndAmount.parse(amount));
    }

    private Milestone settledMilestone() {
        LocalDate date = LocalDate.of(2036, 12, 15);
        Milestone milestone = Milestone.reconstitute(UUID.randomUUID().toString(), contract.getContractId().value(), 1,
                new BigDecimal("10000"), date, 2, date.minusDays(10), MilestoneStatus.CONTESTED, FundingStatus.LOCKED,
                VndAmount.parse("15000.00"), VndAmount.parse("150000000.00"), AT, AT, 0, null, null, null, null,
                new MilestoneFlag(FlagIssueType.QUALITY, AT, AT, ContestedBy.SELLER, UUID.randomUUID().toString()));
        milestone.settleDisputeResolved(new BigDecimal("9800"), VndAmount.parse("144060000.00"),
                VndAmount.parse("5940000.00"), AT);
        return milestone;
    }

    private static Contract contract() {
        CanonicalTerms terms = CanonicalTerms.of(snapshot(), new JcsTermsCanonicalizer());
        String hash = terms.signedContentHash();
        return Contract.reconstitute(new ContractId(UUID.randomUUID().toString()), goods().listingId().toString(),
                BUYER, SELLER, "Cà phê", "HTX Mua", "HTX Bán", "buyer@example.com", "seller@example.com", null, terms,
                ContractStatus.ACTIVE, Map.of(SignerRole.BUYER, hash, SignerRole.SELLER, hash), 0L);
    }
}
