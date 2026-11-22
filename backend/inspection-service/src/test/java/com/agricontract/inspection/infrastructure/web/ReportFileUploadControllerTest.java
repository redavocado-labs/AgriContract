package com.agricontract.inspection.infrastructure.web;

import com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException;
import com.agricontract.inspection.application.port.ReportFileStore;
import com.agricontract.inspection.application.usecase.UploadLevel15ReportFile;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportFileUploadControllerTest {
    final UploadLevel15ReportFile upload = mock(UploadLevel15ReportFile.class);
    final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReportFileUploadController(upload))
            .setControllerAdvice(new ReportEndpointErrors()).build();
    final String contract = UUID.randomUUID().toString();
    final String milestone = UUID.randomUUID().toString();
    final String path = "/api/v1/inspections/" + contract + "/milestones/" + milestone + "/report-files";

    @Test void returnsAcceptedForValidatedUpload() throws Exception {
        when(upload.upload(eq(contract), eq(milestone), eq("key"), eq("application/pdf"), eq(3L), any()))
                .thenReturn(new ReportFileStore.StoredFile(UUID.randomUUID().toString(), "PROCESSING"));
        mvc.perform(post(path).header("Idempotency-Key", "key").contentType("application/pdf").content(new byte[]{1,2,3}).header("Content-Length", "3"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test void rejectsBadTypeAndOversizeBeforeCallingUpload() throws Exception {
        mvc.perform(post(path).header("Idempotency-Key", "key").contentType("text/plain").header("Content-Length", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Idempotency-Key", "key").contentType("application/pdf").header("Content-Length", "10485761"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post(path).header("Idempotency-Key", "key").contentType("application/pdf")
                        .header("Content-Length", "1").header("Transfer-Encoding", "chunked"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(upload);
    }

    @Test void assignmentDenialUsesForbidden() throws Exception {
        when(upload.upload(anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenThrow(new InspectionAssignmentAccessDeniedException());
        mvc.perform(post(path).header("Idempotency-Key", "key").contentType("application/pdf").header("Content-Length", "1"))
                .andExpect(status().isForbidden());
    }

    @Test void bodyLengthMismatchIsBadRequest() throws Exception {
        when(upload.upload(anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenThrow(new com.agricontract.inspection.infrastructure.files.FileServiceClient.InvalidUpload());
        mvc.perform(post(path).header("Idempotency-Key", "key").contentType("application/pdf").header("Content-Length", "3"))
                .andExpect(status().isBadRequest());
    }

    @Test void malformedPathUsesSharedEnvelopeAndFallbackTrace() throws Exception {
        mvc.perform(post("/api/v1/inspections/not-a-uuid/milestones/" + milestone + "/report-files"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.traceId").isNotEmpty()).andExpect(jsonPath("$.message").value("Malformed request"));
    }

    @Test void unexpectedExceptionDoesNotExposeDetails() throws Exception {
        when(upload.upload(anyString(), anyString(), anyString(), anyString(), anyLong(), any()))
                .thenThrow(new IllegalStateException("private SQL details"));
        mvc.perform(post(path).header("Idempotency-Key", "key").header("X-Correlation-Id", "trace-123")
                        .contentType("application/pdf").header("Content-Length", "1"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.traceId").value("trace-123"));
    }
}
