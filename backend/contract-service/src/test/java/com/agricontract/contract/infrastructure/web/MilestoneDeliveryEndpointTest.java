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
import com.agricontract.contract.application.usecase.ConfirmMilestoneCleanUseCase;
import com.agricontract.contract.application.usecase.FlagMilestoneUseCase;
import com.agricontract.contract.application.usecase.GetMilestoneDetailUseCase;
import com.agricontract.contract.application.usecase.ReadMilestoneEvidenceUseCase;
import com.agricontract.contract.application.usecase.RecordBuyerReceiptUseCase;
import com.agricontract.contract.application.usecase.RecordSellerWeighedUseCase;
import com.agricontract.contract.application.usecase.RespondToMilestoneFlagUseCase;
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
    private final RecordBuyerReceiptUseCase receive = mock(RecordBuyerReceiptUseCase.class);
    private final ConfirmMilestoneCleanUseCase confirm = mock(ConfirmMilestoneCleanUseCase.class);
    private final FlagMilestoneUseCase flag = mock(FlagMilestoneUseCase.class);
    private final RespondToMilestoneFlagUseCase respond = mock(RespondToMilestoneFlagUseCase.class);
    private final GetMilestoneDetailUseCase detail = mock(GetMilestoneDetailUseCase.class);
    private final ReadMilestoneEvidenceUseCase readEvidence = mock(ReadMilestoneEvidenceUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        MilestoneDeliveryController controller = new MilestoneDeliveryController(upload, weigh, receive, confirm, flag, respond,
                detail, readEvidence, new ObjectMapper());
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

    @Test
    void receive_isBuyerOnly_checksPartyThenMilestone_beforeTheMalformedBodyIsParsed() throws Exception {
        for (String role : List.of("SELLER", "ADMIN", "OPERATOR", "ARBITRATOR", "INSPECTOR")) {
            mvc.perform(receiveRequest(role, "{not json")).andExpect(status().isForbidden());
        }
        doThrow(new com.agricontract.contract.domain.exception.UnauthorizedContractAccessException("x"))
                .doThrow(new MilestoneNotFoundException(CONTRACT.toString(), MILESTONE.toString()))
                .doNothing()
                .when(receive).requireBuyerOfMilestone(CONTRACT.toString(), MILESTONE.toString(), "user-1");

        mvc.perform(receiveRequest("BUYER", "{not json")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(receiveRequest("BUYER", "{not json")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MILESTONE_NOT_FOUND"));
        mvc.perform(receiveRequest("BUYER", "{not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(receive, never()).execute(any());
    }

    @Test
    void aReceive_isMappedToTheCommand_204_andRefusalsCarryTheirCodes() throws Exception {
        UUID key = UUID.randomUUID();
        String received = "{\"buyerReceivedWeight\":9400.500,\"buyerEvidenceFileId\":\"" + PHOTO + "\","
                + "\"consent\":{\"statementVersion\":\"1.0\",\"presentedContentHash\":\"" + HASH + "\"}}";

        mvc.perform(post("/api/v1/contracts/{c}/milestones/{m}/receive", CONTRACT, MILESTONE)
                        .principal(auth("BUYER")).header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(received))
                .andExpect(status().isNoContent());

        ArgumentCaptor<MilestoneEvidenceCommands.Receive> command =
                ArgumentCaptor.forClass(MilestoneEvidenceCommands.Receive.class);
        verify(receive).execute(command.capture());
        assertThat(command.getValue().buyerReceivedWeight()).isEqualByComparingTo(new BigDecimal("9400.5"));
        assertThat(command.getValue()).usingRecursiveComparison().ignoringFields("buyerReceivedWeight")
                .isEqualTo(new MilestoneEvidenceCommands.Receive(CONTRACT.toString(), MILESTONE.toString(), "user-1",
                        key, null, PHOTO.toString(), null, "1.0", HASH));

        doThrow(new MilestoneRequestInvalidException(MilestoneRequestInvalidException.EVIDENCE_NOT_UPLOADED, "x"))
                .doThrow(new ContractConflictException(ContractConflictException.MILESTONE_ACTION_NOT_ALLOWED, "x"))
                .when(receive).execute(any());
        mvc.perform(receiveRequest("BUYER", received)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVIDENCE_NOT_UPLOADED"));
        mvc.perform(receiveRequest("BUYER", received)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MILESTONE_ACTION_NOT_ALLOWED"));
        for (String bad : List.of(received.replace("9400.500", "0"), received.replace("}}", "},\"extra\":1}"),
                received.replace("\"buyerEvidenceFileId\":\"" + PHOTO + "\",", ""))) {
            mvc.perform(receiveRequest("BUYER", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void confirm_isBuyerOnly_checksPartyThenMilestone_thenTheBody_andReachesTheUseCase() throws Exception {
        String good = "{\"statementVersion\":\"1.0\",\"presentedContentHash\":\"" + HASH + "\"}";
        for (String role : List.of("SELLER", "ADMIN", "OPERATOR", "ARBITRATOR", "INSPECTOR")) {
            mvc.perform(confirmRequest(role, good)).andExpect(status().isForbidden());
        }
        doThrow(new MilestoneNotFoundException(CONTRACT.toString(), MILESTONE.toString())).doNothing()
                .when(confirm).requireBuyerOfMilestone(CONTRACT.toString(), MILESTONE.toString(), "user-1");

        mvc.perform(confirmRequest("BUYER", "{not json")).andExpect(status().isNotFound());
        mvc.perform(confirmRequest("BUYER", "{not json")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(confirmRequest("BUYER", good.replace("}", ",\"extra\":1}"))).andExpect(status().isBadRequest());
        mvc.perform(confirmRequest("BUYER", "{\"statementVersion\":\"1.0\"}")).andExpect(status().isBadRequest());
        verify(confirm, never()).execute(any());

        mvc.perform(confirmRequest("BUYER", good)).andExpect(status().isNoContent());
        ArgumentCaptor<MilestoneEvidenceCommands.Confirm> command =
                ArgumentCaptor.forClass(MilestoneEvidenceCommands.Confirm.class);
        verify(confirm).execute(command.capture());
        assertThat(command.getValue().statementVersion()).isEqualTo("1.0");
        assertThat(command.getValue().presentedContentHash()).isEqualTo(HASH);
        assertThat(command.getValue().actorId()).isEqualTo("user-1");
    }

    @Test
    void flag_isBuyerOnly_hasAWrappedConsent_andReachesTheUseCaseWithTheIssueType() throws Exception {
        String consent = "\"consent\":{\"statementVersion\":\"1.0\",\"presentedContentHash\":\"" + HASH + "\"}";
        String good = "{\"issueType\":\"QUALITY\"," + consent + "}";
        for (String role : List.of("SELLER", "ADMIN", "OPERATOR", "ARBITRATOR", "INSPECTOR")) {
            mvc.perform(flagRequest(role, good)).andExpect(status().isForbidden());
        }
        doThrow(new MilestoneNotFoundException(CONTRACT.toString(), MILESTONE.toString())).doNothing()
                .when(flag).requireBuyerOfMilestone(CONTRACT.toString(), MILESTONE.toString(), "user-1");

        mvc.perform(flagRequest("BUYER", "{not json")).andExpect(status().isNotFound());
        mvc.perform(flagRequest("BUYER", "{not json")).andExpect(status().isBadRequest());
        mvc.perform(flagRequest("BUYER", "{\"issueType\":\"OTHER\"," + consent + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(flagRequest("BUYER", "{" + consent + "}")).andExpect(status().isBadRequest());
        mvc.perform(flagRequest("BUYER", "{\"issueType\":\"QUANTITY\"}")).andExpect(status().isBadRequest());
        mvc.perform(flagRequest("BUYER", good.replace("}}", "},\"extra\":1}"))).andExpect(status().isBadRequest());
        verify(flag, never()).execute(any());

        mvc.perform(flagRequest("BUYER", good)).andExpect(status().isNoContent());
        ArgumentCaptor<MilestoneEvidenceCommands.Flag> command =
                ArgumentCaptor.forClass(MilestoneEvidenceCommands.Flag.class);
        verify(flag).execute(command.capture());
        assertThat(command.getValue().issueType())
                .isEqualTo(com.agricontract.contract.domain.model.milestone.FlagIssueType.QUALITY);
        assertThat(command.getValue().presentedContentHash()).isEqualTo(HASH);
        assertThat(command.getValue().actorId()).isEqualTo("user-1");
    }

    @Test
    void respond_isSellerOnly_hasAFlatConsent_andReachesTheUseCase() throws Exception {
        String good = "{\"statementVersion\":\"1.0\",\"presentedContentHash\":\"" + HASH + "\"}";
        for (String role : List.of("BUYER", "ADMIN", "OPERATOR", "ARBITRATOR", "INSPECTOR")) {
            mvc.perform(respondRequest(role, good)).andExpect(status().isForbidden());
        }
        doThrow(new MilestoneNotFoundException(CONTRACT.toString(), MILESTONE.toString())).doNothing()
                .when(respond).requireSellerOfMilestone(CONTRACT.toString(), MILESTONE.toString(), "user-1");

        mvc.perform(respondRequest("SELLER", "{not json")).andExpect(status().isNotFound());
        mvc.perform(respondRequest("SELLER", "{not json")).andExpect(status().isBadRequest());
        mvc.perform(respondRequest("SELLER", good.replace("}", ",\"extra\":1}"))).andExpect(status().isBadRequest());
        verify(respond, never()).execute(any());

        mvc.perform(respondRequest("SELLER", good)).andExpect(status().isNoContent());
        ArgumentCaptor<MilestoneEvidenceCommands.Respond> command =
                ArgumentCaptor.forClass(MilestoneEvidenceCommands.Respond.class);
        verify(respond).execute(command.capture());
        assertThat(command.getValue().presentedContentHash()).isEqualTo(HASH);
    }

    private static MockHttpServletRequestBuilder flagRequest(String role, String body) {
        return post("/api/v1/contracts/{c}/milestones/{m}/flag", CONTRACT, MILESTONE).principal(auth(role))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder respondRequest(String role, String body) {
        return post("/api/v1/contracts/{c}/milestones/{m}/respond", CONTRACT, MILESTONE).principal(auth(role))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder confirmRequest(String role, String body) {
        return post("/api/v1/contracts/{c}/milestones/{m}/confirm", CONTRACT, MILESTONE).principal(auth(role))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder receiveRequest(String role, String body) {
        return post("/api/v1/contracts/{c}/milestones/{m}/receive", CONTRACT, MILESTONE).principal(auth(role))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
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
