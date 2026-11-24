package com.agricontract.contract.infrastructure.web;

import com.agricontract.contract.application.dto.RetryRemedyLegsCommand;
import com.agricontract.contract.application.exception.UnauthorizedContractActionException;
import com.agricontract.contract.application.usecase.RetryRemedyLegsUseCase;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Technical recovery of remedy legs that ran out of retries (D93). OPERATOR only; never changes money or decision. */
@RestController
@RequestMapping("/api/v1/contracts")
@RequiredArgsConstructor
public class RemedyRecoveryController {

    private final RetryRemedyLegsUseCase retryRemedyLegs;

    /** 204 while the contract is ACTIVE: escrow resends only its FAILED remedy legs, none is a no-op. */
    @PostMapping("/{contractId}/remedy/retry-legs")
    @Operation(summary = "OPERATOR: resend the failed remedy legs after fixing the cause")
    public ResponseEntity<Void> retryRemedyLegs(
            @PathVariable UUID contractId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            Authentication auth) {
        boolean operator = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_OPERATOR"::equals);
        if (!operator) {
            throw new UnauthorizedContractActionException("Only OPERATOR can retry remedy legs.");
        }
        retryRemedyLegs.execute(new RetryRemedyLegsCommand(contractId.toString(), auth.getName(), idempotencyKey));
        return ResponseEntity.noContent().build();
    }
}
