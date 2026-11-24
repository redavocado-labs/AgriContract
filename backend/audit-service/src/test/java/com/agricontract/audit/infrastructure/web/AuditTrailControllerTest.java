package com.agricontract.audit.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agricontract.audit.application.dto.AuditTrailView;
import com.agricontract.audit.application.dto.ChainVerificationResult;
import com.agricontract.audit.application.exception.AuditTrailNotFoundException;
import com.agricontract.audit.application.usecase.ReadAuditTrailUseCase;
import com.agricontract.audit.domain.model.AuditRecord;
import com.agricontract.audit.domain.model.ChainedAuditRecord;
import com.agricontract.audit.domain.model.StoredAuditRecord;
import com.agricontract.audit.domain.model.SubjectType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** D97 web layer: gateway identity, role per path, parameters, and the party response without link hashes. */
class AuditTrailControllerTest {
    private static final String SECRET = "test-gateway-secret";
    private static final String CONTRACT = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();

    private final ReadAuditTrailUseCase trails = mock(ReadAuditTrailUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ZoneOffset.UTC);
        mvc = MockMvcBuilders.standaloneSetup(new AuditTrailController(trails, clock))
                .setControllerAdvice(new AuditExceptionHandler())
                .addFilters(new GatewayIdentityFilter(SECRET, new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    @Test
    void withoutAValidGatewayIdentity_401() throws Exception {
        mvc.perform(get("/api/v1/audit/contracts/" + CONTRACT + "/records")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(as("BUYER", "/api/v1/audit/contracts/" + CONTRACT + "/records").header("X-Gateway-Secret", "x"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/audit/contracts/" + CONTRACT + "/records").header("X-Gateway-Secret", SECRET)
                .header("X-User-Id", "not-a-uuid").header("X-User-Role", "BUYER")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/audit/contracts/" + CONTRACT + "/records").header("X-Gateway-Secret", SECRET)
                .header("X-User-Id", USER).header("X-User-Role", "ROOT")).andExpect(status().isUnauthorized());
    }

    @Test
    void eachPath_takesOnlyItsRole_noAdminFallback() throws Exception {
        for (String role : List.of("ADMIN", "OPERATOR", "ARBITRATOR", "INSPECTOR")) {
            mvc.perform(as(role, "/api/v1/audit/contracts/" + CONTRACT + "/records")).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        for (String role : List.of("BUYER", "SELLER", "OPERATOR", "ARBITRATOR")) {
            mvc.perform(as(role, "/api/v1/admin/audit/records")).andExpect(status().isForbidden());
            mvc.perform(as(role, "/api/v1/admin/audit/verification")).andExpect(status().isForbidden());
            mvc.perform(as(role, "/api/v1/admin/audit/contracts/" + CONTRACT + "/records"))
                    .andExpect(status().isForbidden());
        }
        verify(trails, never()).globalForAdmin(any(), anyInt(), anyInt(), any());
    }

    @Test
    void thePartyResponse_hasNoLinkHashes_andTheAdminOneHasThem() throws Exception {
        StoredAuditRecord record = stored();
        AuditTrailView view = new AuditTrailView(CONTRACT, record.recordHash(), false, List.of(record));
        when(trails.forParty(CONTRACT, USER)).thenReturn(view);
        when(trails.forAdmin(CONTRACT, USER)).thenReturn(view);

        mvc.perform(as("BUYER", "/api/v1/audit/contracts/" + CONTRACT + "/records")).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRecordHash").value(record.recordHash()))
                .andExpect(jsonPath("$.anchorStatus").value("NOT_ANCHORED"))
                .andExpect(jsonPath("$.records[0].sourceType").value("MILESTONE_EVENT"))
                .andExpect(jsonPath("$.records[0].prevHashSubject").doesNotExist())
                .andExpect(jsonPath("$.records[0].prevHashGlobal").doesNotExist())
                .andExpect(jsonPath("$.records[0].globalSeq").doesNotExist());
        mvc.perform(as("ADMIN", "/api/v1/admin/audit/contracts/" + CONTRACT + "/records")).andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].prevHashGlobal").value("b".repeat(64)))
                .andExpect(jsonPath("$.records[0].globalSeq").value(7));
    }

    @Test
    void notFound_badParams_andTheVerificationShape() throws Exception {
        when(trails.forParty(eq(CONTRACT), any())).thenThrow(new AuditTrailNotFoundException(CONTRACT));
        mvc.perform(as("SELLER", "/api/v1/audit/contracts/" + CONTRACT + "/records")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AUDIT_TRAIL_NOT_FOUND"));
        mvc.perform(as("BUYER", "/api/v1/audit/contracts/abc/records")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        for (String q : List.of("subjectType=USER", "page=-1", "size=0", "size=101", "page=x")) {
            mvc.perform(as("ADMIN", "/api/v1/admin/audit/records?" + q)).andExpect(status().isBadRequest());
        }

        String broken = UUID.randomUUID().toString();
        when(trails.verifyForParty(CONTRACT, USER)).thenReturn(new ChainVerificationResult(false, 3, List.of(
                new ChainVerificationResult.Violation(broken, 9, ChainVerificationResult.Reason.PREV_SUBJECT_MISMATCH),
                new ChainVerificationResult.Violation(UUID.randomUUID().toString(), 12,
                        ChainVerificationResult.Reason.HASH_MISMATCH))));
        mvc.perform(as("BUYER", "/api/v1/audit/contracts/" + CONTRACT + "/verification")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("CONTRACT"))
                .andExpect(jsonPath("$.status").value("BROKEN"))
                .andExpect(jsonPath("$.brokenRecordId").value(broken))
                .andExpect(jsonPath("$.brokenReason").value("SUBJECT_LINK_BROKEN"))
                .andExpect(jsonPath("$.checkedRecords").value(3));
        when(trails.verifyGlobal()).thenReturn(new ChainVerificationResult(true, 40, List.of()));
        mvc.perform(as("ADMIN", "/api/v1/admin/audit/verification")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("GLOBAL")).andExpect(jsonPath("$.status").value("INTACT"))
                .andExpect(jsonPath("$.contractId").isEmpty()).andExpect(jsonPath("$.brokenReason").isEmpty());
    }

    private static MockHttpServletRequestBuilder as(String role, String path) {
        return get(path).header("X-Gateway-Secret", SECRET).header("X-User-Id", USER).header("X-User-Role", role);
    }

    private static StoredAuditRecord stored() {
        AuditRecord record = new AuditRecord(UUID.randomUUID().toString(), SubjectType.CONTRACT, CONTRACT,
                "MILESTONE_EVENT", "milestone.seller_weighed", UUID.randomUUID().toString(), null, null, null,
                Instant.parse("2026-11-24T02:00:00Z"), Map.of("contractId", CONTRACT, "sellerDeclaredWeight", "10000"),
                Instant.parse("2026-11-24T02:00:01Z"));
        ChainedAuditRecord chained = ChainedAuditRecord.chain(record, "b".repeat(64), "c".repeat(64));
        return new StoredAuditRecord(record.recordId(), 7, chained.recordHash(), "b".repeat(64), "c".repeat(64),
                chained);
    }
}
