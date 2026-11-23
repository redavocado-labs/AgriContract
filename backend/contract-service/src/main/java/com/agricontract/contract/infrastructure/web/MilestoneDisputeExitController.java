package com.agricontract.contract.infrastructure.web;

import com.agricontract.contract.application.dto.DisputeExitCommands;
import com.agricontract.contract.application.exception.UnauthorizedContractActionException;
import com.agricontract.contract.application.usecase.DisputeExitUseCases;
import com.agricontract.contract.domain.model.dispute.DisputeExitType;
import com.agricontract.contract.domain.model.terms.VndAmount;
import com.agricontract.contract.infrastructure.config.GatewayAuthDetails;
import com.agricontract.contract.infrastructure.web.dto.InitiateDisputeConcessionRequest;
import com.agricontract.contract.infrastructure.web.dto.InitiateDisputeSettlementRequest;
import com.agricontract.contract.infrastructure.web.dto.InitiateSignResponse;
import com.agricontract.contract.infrastructure.web.dto.VerifyOtpAndSignRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Ending a milestone dispute by the parties (D57, D92): concession and agreed settlement, both with the high-risk
 * consent (checkbox + fresh login + emailed code). Order: role (403), contract and milestone (404), party (403), the
 * body (400), then the use case (state, 409).
 */
@RestController
@RequestMapping("/api/v1/contracts/{contractId}/milestones/{milestoneId}/dispute")
public class MilestoneDisputeExitController {

    private final DisputeExitUseCases exits;
    private final ObjectReader concedeReader;
    private final ObjectReader settleReader;
    private final ObjectReader verifyReader;

    public MilestoneDisputeExitController(DisputeExitUseCases exits, ObjectMapper objectMapper) {
        this.exits = exits;
        this.concedeReader = objectMapper.readerFor(InitiateDisputeConcessionRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.settleReader = objectMapper.readerFor(InitiateDisputeSettlementRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.verifyReader = objectMapper.readerFor(VerifyOtpAndSignRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostMapping(path = "/concede/initiate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "initiateDisputeConcession",
            summary = "Party: accept the other party's position (checkbox + fresh login + emailed code)")
    public ResponseEntity<InitiateSignResponse> initiateConcession(
            @PathVariable UUID contractId, @PathVariable UUID milestoneId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, @RequestBody String body, Authentication auth) {
        requireParty(auth, contractId, milestoneId);
        InitiateDisputeConcessionRequest request = read(concedeReader, body, "InitiateDisputeConcessionRequest");
        var view = exits.initiate(DisputeExitType.CONCEDE, new DisputeExitCommands.Initiate(contractId.toString(),
                milestoneId.toString(), auth.getName(), idempotencyKey, null, request.consentStatementVersion(),
                request.presentedContentHash(), authTime(auth)));
        return ResponseEntity.ok(InitiateSignResponse.from(view));
    }

    @PostMapping(path = "/concede/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "verifyDisputeConcession", summary = "Party: confirm the concession with the emailed code")
    public ResponseEntity<Void> verifyConcession(
            @PathVariable UUID contractId, @PathVariable UUID milestoneId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, @RequestBody String body, Authentication auth) {
        requireParty(auth, contractId, milestoneId);
        VerifyOtpAndSignRequest request = read(verifyReader, body, "VerifyOtpAndSignRequest");
        exits.verify(DisputeExitType.CONCEDE, new DisputeExitCommands.Verify(contractId.toString(),
                milestoneId.toString(), auth.getName(), idempotencyKey, request.otpId(), request.otpCode(),
                authTime(auth)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/settlement/initiate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "initiateDisputeSettlement",
            summary = "Party: propose or accept an agreed seller entitlement (checkbox + fresh login + emailed code)")
    public ResponseEntity<InitiateSignResponse> initiateSettlement(
            @PathVariable UUID contractId, @PathVariable UUID milestoneId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, @RequestBody String body, Authentication auth) {
        requireParty(auth, contractId, milestoneId);
        InitiateDisputeSettlementRequest request = read(settleReader, body, "InitiateDisputeSettlementRequest");
        var view = exits.initiate(DisputeExitType.SETTLE, new DisputeExitCommands.Initiate(contractId.toString(),
                milestoneId.toString(), auth.getName(), idempotencyKey,
                VndAmount.parse(request.agreedSellerEntitlementAmount()), request.consentStatementVersion(),
                request.presentedContentHash(), authTime(auth)));
        return ResponseEntity.ok(InitiateSignResponse.from(view));
    }

    @PostMapping(path = "/settlement/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "verifyDisputeSettlement",
            summary = "Party: confirm the settlement proposal or acceptance with the emailed code")
    public ResponseEntity<Void> verifySettlement(
            @PathVariable UUID contractId, @PathVariable UUID milestoneId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, @RequestBody String body, Authentication auth) {
        requireParty(auth, contractId, milestoneId);
        VerifyOtpAndSignRequest request = read(verifyReader, body, "VerifyOtpAndSignRequest");
        exits.verify(DisputeExitType.SETTLE, new DisputeExitCommands.Verify(contractId.toString(),
                milestoneId.toString(), auth.getName(), idempotencyKey, request.otpId(), request.otpCode(),
                authTime(auth)));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/settlement")
    @Operation(operationId = "withdrawDisputeSettlement",
            summary = "Party: withdraw your open proposal, or refuse the other party's")
    public ResponseEntity<Void> withdrawSettlement(
            @PathVariable UUID contractId, @PathVariable UUID milestoneId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey, Authentication auth) {
        requireParty(auth, contractId, milestoneId);
        exits.withdraw(new DisputeExitCommands.Withdraw(contractId.toString(), milestoneId.toString(),
                auth.getName(), idempotencyKey));
        return ResponseEntity.noContent().build();
    }

    private void requireParty(Authentication auth, UUID contractId, UUID milestoneId) {
        boolean party = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> role.equals("ROLE_BUYER") || role.equals("ROLE_SELLER"));
        if (!party) {
            throw new UnauthorizedContractActionException("Only BUYER or SELLER can end a milestone dispute.");
        }
        exits.requirePartyOfMilestone(contractId.toString(), milestoneId.toString(), auth.getName());
    }

    private static Instant authTime(Authentication auth) {
        return auth.getDetails() instanceof GatewayAuthDetails details ? details.authTime() : null;
    }

    /** Never echoes the body: a verify body holds the code. */
    private static <T> T read(ObjectReader reader, String body, String type) {
        try {
            return reader.readValue(body);
        } catch (JsonProcessingException unreadable) {
            if (unreadable.getCause() instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("Body is not a valid " + type + ": "
                    + unreadable.getOriginalMessage(), unreadable);
        }
    }
}
