package com.agricontract.audit.infrastructure.web;

import com.agricontract.audit.application.usecase.ReadAuditTrailUseCase;
import com.agricontract.audit.domain.model.SubjectType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.regex.Pattern;

/**
 * The evidence log API of hash-chain §4.6 (D97): /api/v1/audit/** for a contract's BUYER or SELLER (no fallback:
 * ADMIN uses /api/v1/admin/audit/**), /api/v1/admin/audit/** for ADMIN only. Params arrive as text so a malformed
 * value is the same 400 VALIDATION_ERROR as the other rule breaks.
 */
@RestController
@RequiredArgsConstructor
public class AuditTrailController {

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ReadAuditTrailUseCase trails;
    private final Clock clock;

    @GetMapping("/api/v1/audit/contracts/{contractId}/records")
    public AuditResponses.ContractAuditTrail partyRecords(@PathVariable String contractId,
            @RequestAttribute(GatewayIdentityFilter.VIEWER) GatewayIdentityFilter.Viewer viewer) {
        requireParty(viewer);
        return AuditResponses.ContractAuditTrail.of(trails.forParty(uuid(contractId), viewer.userId()));
    }

    @GetMapping("/api/v1/audit/contracts/{contractId}/verification")
    public AuditResponses.AuditChainVerification partyVerification(@PathVariable String contractId,
            @RequestAttribute(GatewayIdentityFilter.VIEWER) GatewayIdentityFilter.Viewer viewer) {
        requireParty(viewer);
        String id = uuid(contractId);
        return AuditResponses.AuditChainVerification.of(id, trails.verifyForParty(id, viewer.userId()), clock);
    }

    @GetMapping("/api/v1/admin/audit/contracts/{contractId}/records")
    public AuditResponses.AdminContractAuditTrail adminRecords(@PathVariable String contractId,
            @RequestAttribute(GatewayIdentityFilter.VIEWER) GatewayIdentityFilter.Viewer viewer) {
        requireAdmin(viewer);
        return AuditResponses.AdminContractAuditTrail.of(trails.forAdmin(uuid(contractId), viewer.userId()));
    }

    @GetMapping("/api/v1/admin/audit/contracts/{contractId}/verification")
    public AuditResponses.AuditChainVerification adminVerification(@PathVariable String contractId,
            @RequestAttribute(GatewayIdentityFilter.VIEWER) GatewayIdentityFilter.Viewer viewer) {
        requireAdmin(viewer);
        String id = uuid(contractId);
        return AuditResponses.AuditChainVerification.of(id, trails.verifyForAdmin(id), clock);
    }

    @GetMapping("/api/v1/admin/audit/records")
    public AuditResponses.AdminAuditRecordPage adminGlobal(@RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String page, @RequestParam(required = false) String size,
            @RequestAttribute(GatewayIdentityFilter.VIEWER) GatewayIdentityFilter.Viewer viewer) {
        requireAdmin(viewer);
        SubjectType filter = null;
        if (subjectType != null) {
            try {
                filter = SubjectType.valueOf(subjectType);
            } catch (IllegalArgumentException e) {
                throw AuditApiException.invalid("subjectType must be CONTRACT, USER_PAIR or SYSTEM");
            }
        }
        int p = integer("page", page, 0, 0, Integer.MAX_VALUE / 100);
        int s = integer("size", size, 20, 1, 100);
        return AuditResponses.AdminAuditRecordPage.of(trails.globalForAdmin(filter, p, s, viewer.userId()));
    }

    @GetMapping("/api/v1/admin/audit/verification")
    public AuditResponses.AuditChainVerification adminGlobalVerification(
            @RequestAttribute(GatewayIdentityFilter.VIEWER) GatewayIdentityFilter.Viewer viewer) {
        requireAdmin(viewer);
        return AuditResponses.AuditChainVerification.of(null, trails.verifyGlobal(), clock);
    }

    private static void requireParty(GatewayIdentityFilter.Viewer viewer) {
        if (!viewer.has("BUYER") && !viewer.has("SELLER")) {
            throw AuditApiException.forbidden("Only a buyer or seller reads a contract's evidence log here");
        }
    }

    private static void requireAdmin(GatewayIdentityFilter.Viewer viewer) {
        if (!viewer.has("ADMIN")) {
            throw AuditApiException.forbidden("Only ADMIN reads the whole evidence log");
        }
    }

    private static String uuid(String value) {
        if (!UUID.matcher(value).matches()) {
            throw AuditApiException.invalid("contractId is not a UUID");
        }
        return value.toLowerCase();
    }

    private static int integer(String name, String value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw AuditApiException.invalid(name + " is out of range");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw AuditApiException.invalid(name + " is not an integer");
        }
    }
}
