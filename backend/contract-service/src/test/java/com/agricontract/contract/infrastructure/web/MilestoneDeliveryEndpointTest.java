package com.agricontract.contract.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.FileRejectedException;
import com.agricontract.contract.application.exception.FileServiceUnavailableException;
import com.agricontract.contract.application.exception.MilestoneNotFoundException;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.usecase.GetMilestoneDetailUseCase;
import com.agricontract.contract.application.usecase.ReadMilestoneEvidenceUseCase;
import com.agricontract.contract.application.usecase.RecordSellerWeighedUseCase;
import com.agricontract.contract.application.usecase.UploadMilestoneEvidenceUseCase;
import com.agricontract.contract.domain.exception.ContractConflictException;
import com.agricontract.contract.domain.exception.MilestoneRequestInvalidException;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** POST .../milestones/{milestoneId}/evidence and /weigh (golden-flow-api uploadMilestoneEvidence, recordSellerWeighed). */
class MilestoneDeliveryEndpointTest {
    private static final UUID CONTRACT = UUID.randomUUID();
    private static final UUID MILESTONE = UUID.randomUUID();
    private static final UUID PHOTO = UUID.randomUUID();
    private static final String HASH = "a".repeat(64);

    private final UploadMilestoneEvidenceUseCase upload = mock(UploadMilestoneEvidenceUseCase.class);
    private final RecordSellerWeighedUseCase weigh = mock(RecordSellerWeighedUseCase.class);
    private final GetMilestoneDetailUseCase detail = mock(GetMilestoneDetailUseCase.class);
    private final ReadMilestoneEvidenceUseCase readEvidence = mock(ReadMilestoneEvidenceUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MilestoneDeliveryController controller = new MilestoneDeliveryController(upload, weigh, detail,
                readEvidence, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void anUpload_isStreamedWithItsTypeAndLength_and202CarriesTheFileAndStatus() throws Exception {
        String fileId = UUID.randomUUID().toString();
        when(upload.execute(any(), any())).thenReturn(new MilestoneEvidenceCommands.Uploaded(fileId,
                EvidenceScanStatus.PROCESSING));
        UUID key = UUID.randomUUID();

        mvc.perform(evidence("SELLER").header("Idempotency-Key", key.toString()).contentType("image/png")
                        .content(new byte[] {1, 2, 3}))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileId").value(fileId))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        ArgumentCaptor<MilestoneEvidenceCommands.Upload> command =
                ArgumentCaptor.forClass(MilestoneEvidenceCommands.Upload.class);
        verify(upload).execute(command.capture(), any());
        assertThat(command.getValue()).isEqualTo(new MilestoneEvidenceCommands.Upload(CONTRACT.toString(),
                MILESTONE.toString(), "user-1", key, "image/png", 3));
    }

    @Test
    void uploadErrors_keepTheirStatusAndCode() throws Exception {
        when(upload.execute(any(), any()))
                .thenThrow(new FileRejectedException(413, FileRejectedException.FILE_TOO_LARGE, "big"))
                .thenThrow(new FileServiceUnavailableException("down", null))
                .thenThrow(new MilestoneNotFoundException(CONTRACT.toString(), MILESTONE.toString()));

        mvc.perform(evidence("BUYER").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/pdf").content(new byte[] {1}))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        mvc.perform(evidence("BUYER").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/pdf").content(new byte[] {1}))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FILE_SERVICE_UNAVAILABLE"));
        mvc.perform(evidence("BUYER").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/pdf").content(new byte[] {1}))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MILESTONE_NOT_FOUND"));
    }

    @Test
    void staffCannotUpload_norWeigh() throws Exception {
        for (String role : List.of("ADMIN", "OPERATOR", "ARBITRATOR", "INSPECTOR")) {
            mvc.perform(evidence(role).header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType("image/png").content(new byte[] {1})).andExpect(status().isForbidden());
            mvc.perform(weighRequest(role, body("9500.5"))).andExpect(status().isForbidden());
        }
        mvc.perform(weighRequest("BUYER", body("9500.5"))).andExpect(status().isForbidden());
        verify(upload, never()).execute(any(), any());
        verify(weigh, never()).execute(any());
    }

    @Test
    void aWeigh_isMappedToTheCommand_and204() throws Exception {
        UUID key = UUID.randomUUID();

        mvc.perform(post("/api/v1/contracts/{c}/milestones/{m}/weigh", CONTRACT, MILESTONE)
                        .principal(auth("SELLER")).header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body("9500.500")))
                .andExpect(status().isNoContent());

        ArgumentCaptor<MilestoneEvidenceCommands.Weigh> command =
                ArgumentCaptor.forClass(MilestoneEvidenceCommands.Weigh.class);
        verify(weigh).execute(command.capture());
        assertThat(command.getValue().sellerDeclaredWeight()).isEqualByComparingTo(new BigDecimal("9500.5"));
        assertThat(command.getValue()).usingRecursiveComparison().ignoringFields("sellerDeclaredWeight")
                .isEqualTo(new MilestoneEvidenceCommands.Weigh(CONTRACT.toString(), MILESTONE.toString(), "user-1",
                        key, null, PHOTO.toString(), null, "1.0", HASH));
    }

    @Test
    void aBodyOutsideTheSchema_is400_beforeTheUseCase() throws Exception {
        for (String bad : List.of(body("0"), body("1000000.001"), body("1.0001"),
                "{\"sellerDeclaredWeight\":1,\"sellerEvidenceFileId\":\"" + PHOTO + "\"}",
                "{\"sellerDeclaredWeight\":1,\"sellerEvidenceFileId\":\"not-a-uuid\",\"consent\":"
                        + "{\"statementVersion\":\"1.0\",\"presentedContentHash\":\"" + HASH + "\"}}",
                body("1").replace("}}", "},\"extra\":1}"))) {
            mvc.perform(weighRequest("SELLER", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(weighRequest("SELLER", "{not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(weigh, never()).execute(any());
    }

    @Test
    void weighChecks_partyThenMilestone_beforeTheBody() throws Exception {
        doThrow(new com.agricontract.contract.domain.exception.UnauthorizedContractAccessException("x"))
                .doThrow(new MilestoneNotFoundException(CONTRACT.toString(), MILESTONE.toString()))
                .when(weigh).requireSellerOfMilestone(CONTRACT.toString(), MILESTONE.toString(), "user-1");

        mvc.perform(weighRequest("SELLER", body("0"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(weighRequest("SELLER", body("0"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MILESTONE_NOT_FOUND"));
        verify(weigh, never()).execute(any());
    }

    @Test
    void weighRefusals_carryTheirCodes() throws Exception {
        doThrow(new MilestoneRequestInvalidException(MilestoneRequestInvalidException.EVIDENCE_NOT_UPLOADED, "x"))
                .doThrow(new ContractConflictException(ContractConflictException.WEIGH_CORRECTION_CLOSED, "x"))
                .when(weigh).execute(any());

        mvc.perform(weighRequest("SELLER", body("1"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_UPLOADED"));
        mvc.perform(weighRequest("SELLER", body("1"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WEIGH_CORRECTION_CLOSED"));
    }

    @Test
    void theEvidenceBytes_areStreamedWithTheirType_andNotCached() throws Exception {
        UUID file = UUID.randomUUID();
        when(readEvidence.execute(CONTRACT.toString(), MILESTONE.toString(), file.toString(), "user-1",
                java.util.Set.of("BUYER"))).thenReturn(new EvidenceFileStore.FileContent("image/png", 3,
                new java.io.ByteArrayInputStream(new byte[] {7, 8, 9})));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/contracts/{c}/milestones/{m}/evidence/{f}", CONTRACT, MILESTONE, file)
                        .principal(auth("BUYER")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "private, no-store"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .bytes(new byte[] {7, 8, 9}));
    }

    private static String body(String weight) {
        return "{\"sellerDeclaredWeight\":" + weight + ",\"sellerEvidenceFileId\":\"" + PHOTO + "\",\"consent\":"
                + "{\"statementVersion\":\"1.0\",\"presentedContentHash\":\"" + HASH + "\"}}";
    }

    private static MockHttpServletRequestBuilder evidence(String role) {
        return post("/api/v1/contracts/{c}/milestones/{m}/evidence", CONTRACT, MILESTONE).principal(auth(role));
    }

    private static MockHttpServletRequestBuilder weighRequest(String role, String body) {
        return post("/api/v1/contracts/{c}/milestones/{m}/weigh", CONTRACT, MILESTONE).principal(auth(role))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken("user-1", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
