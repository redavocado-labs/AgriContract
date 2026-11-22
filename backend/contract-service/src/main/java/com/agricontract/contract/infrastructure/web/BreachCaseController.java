package com.agricontract.contract.infrastructure.web;

import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.exception.UnauthorizedContractActionException;
import com.agricontract.contract.application.usecase.GetBreachCaseUseCase;
import com.agricontract.contract.application.usecase.ListBreachCasesForArbitrationUseCase;
import com.agricontract.contract.application.usecase.ResolveBreachCaseUseCase;
import com.agricontract.contract.application.usecase.ReviewBreachCaseUseCase;
import com.agricontract.contract.domain.model.breach.BreachCaseStatus;
import com.agricontract.contract.infrastructure.web.dto.BreachCaseResolutionRequest;
import com.agricontract.contract.infrastructure.web.dto.BreachCaseResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BreachCase review, decision and reads (D80, D81, D84). The ARBITRATOR role is checked here, with no fallback: ADMIN
 * and OPERATOR cannot review or resolve. The use cases check the contract and the case.
 */
@RestController
@RequestMapping("/api/v1")
public class BreachCaseController {
    private final ReviewBreachCaseUseCase review;
    private final ResolveBreachCaseUseCase resolve;
    private final GetBreachCaseUseCase get;
    private final ListBreachCasesForArbitrationUseCase list;
    private final ObjectMapper objectMapper;

    public BreachCaseController(ReviewBreachCaseUseCase review, ResolveBreachCaseUseCase resolve,
                                GetBreachCaseUseCase get, ListBreachCasesForArbitrationUseCase list,
                                ObjectMapper objectMapper) {
        this.review = review;
        this.resolve = resolve;
        this.get = get;
        this.list = list;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/contracts/{contractId}/breach-cases/{breachCaseId}/review")
    @Operation(operationId = "reviewBreachCase", summary = "ARBITRATOR: take a REPORTED breach case (UNDER_REVIEW)")
    public ResponseEntity<Void> reviewBreachCase(@PathVariable UUID contractId, @PathVariable UUID breachCaseId,
                                                 @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                 Authentication auth) {
        requireArbitrator(auth, "Only an ARBITRATOR can review a breach case.");
        review.execute(new BreachCaseCommands.Review(contractId.toString(), breachCaseId.toString(),
                auth.getName(), idempotencyKey));
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/contracts/{contractId}/breach-cases/{breachCaseId}/resolve",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resolveBreachCase", summary = "ARBITRATOR: record the AttributionDecision on a case")
    public ResponseEntity<Void> resolveBreachCase(@PathVariable UUID contractId, @PathVariable UUID breachCaseId,
                                                  @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                  @RequestBody String body, Authentication auth) {
        requireArbitrator(auth, "Only an ARBITRATOR can resolve a breach case.");
        // API order: role (403), case (404), then the body (400), then the state (409).
        resolve.requireCaseOfContract(contractId.toString(), breachCaseId.toString());
        BreachCaseResolutionRequest request = BreachCaseResolutionRequest.parse(objectMapper, body);
        resolve.execute(new BreachCaseCommands.Resolve(contractId.toString(), breachCaseId.toString(),
                auth.getName(), idempotencyKey, request.finalBreachingRole(), request.decisionSource(),
                request.evidenceRefs()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/contracts/{contractId}/breach-cases/{breachCaseId}")
    @Operation(operationId = "getBreachCase", summary = "Party or ARBITRATOR: one breach case")
    public ResponseEntity<BreachCaseResponse> getBreachCase(@PathVariable UUID contractId,
                                                            @PathVariable UUID breachCaseId, Authentication auth) {
        return ResponseEntity.ok(BreachCaseResponse.from(get.execute(contractId.toString(),
                breachCaseId.toString(), auth.getName(), roles(auth))));
    }

    @GetMapping("/arbitration/breach-cases")
    @Operation(operationId = "listBreachCasesForArbitration", summary = "ARBITRATOR: the breach case work queue")
    public ResponseEntity<BreachCaseResponse.Page> listBreachCasesForArbitration(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String awaitingApproval,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication auth) {
        requireArbitrator(auth, "Only an ARBITRATOR can read the breach case queue.");
        BreachCaseStatus statusFilter = null;
        if (status != null) {
            try {
                statusFilter = BreachCaseStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("status must be REPORTED, UNDER_REVIEW or RESOLVED");
            }
        }
        if (awaitingApproval != null && !awaitingApproval.equals("true") && !awaitingApproval.equals("false")) {
            throw new IllegalArgumentException("awaitingApproval must be true or false");
        }
        return ResponseEntity.ok(BreachCaseResponse.Page.from(list.execute(statusFilter,
                "true".equals(awaitingApproval), page, size)));
    }

    private static void requireArbitrator(Authentication auth, String message) {
        boolean arbitrator = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ARBITRATOR"::equals);
        if (!arbitrator) {
            throw new UnauthorizedContractActionException(message);
        }
    }

    /** The JWT roles without the ROLE_ prefix. */
    private static Set<String> roles(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).collect(Collectors.toSet());
    }
}
