package com.agricontract.contract.infrastructure.web;

import com.agricontract.contract.application.dto.MilestoneEvidenceCommands;
import com.agricontract.contract.application.exception.UnauthorizedContractActionException;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.application.usecase.GetMilestoneDetailUseCase;
import com.agricontract.contract.application.usecase.ReadMilestoneEvidenceUseCase;
import com.agricontract.contract.application.usecase.RecordSellerWeighedUseCase;
import com.agricontract.contract.application.usecase.UploadMilestoneEvidenceUseCase;
import com.agricontract.contract.infrastructure.web.dto.MilestoneDetailResponse;
import com.agricontract.contract.infrastructure.web.dto.MilestoneEvidenceAccepted;
import com.agricontract.contract.infrastructure.web.dto.SellerWeighedRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Milestone delivery actions of the parties (milestone-escrow design §3.2.1): evidence upload (D71) and the seller's
 * weigh (D72-D75); and the BF-04 reads (D84): the milestone detail and the evidence bytes. The gateway's role is checked here coarsely; the use cases check the party on the contract.
 */
@RestController
@RequestMapping("/api/v1/contracts/{contractId}/milestones/{milestoneId}")
public class MilestoneDeliveryController {

    private final UploadMilestoneEvidenceUseCase upload;
    private final RecordSellerWeighedUseCase weigh;
    private final GetMilestoneDetailUseCase detail;
    private final ReadMilestoneEvidenceUseCase readEvidence;
    private final ObjectReader weighReader;

    public MilestoneDeliveryController(UploadMilestoneEvidenceUseCase upload, RecordSellerWeighedUseCase weigh,
                                       GetMilestoneDetailUseCase detail, ReadMilestoneEvidenceUseCase readEvidence,
                                       ObjectMapper objectMapper) {
        this.upload = upload;
        this.weigh = weigh;
        this.detail = detail;
        this.readEvidence = readEvidence;
        this.weighReader = objectMapper.readerFor(SellerWeighedRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /**
     * Raw file body (image/jpeg, image/png or application/pdf, Content-Length required, at most 10 MiB), streamed to
     * file-service. 202 with the fileId and its scan status.
     */
    @PostMapping("/evidence")
    @Operation(summary = "Party: upload a weighing photo or delivery certificate for this milestone")
    public ResponseEntity<MilestoneEvidenceAccepted> uploadEvidence(
            @PathVariable UUID contractId,
            @PathVariable UUID milestoneId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            HttpServletRequest request,
            Authentication auth) throws IOException {
        requireRole(auth, Set.of("ROLE_BUYER", "ROLE_SELLER"), "Only BUYER or SELLER can upload milestone evidence.");
        var uploaded = upload.execute(new MilestoneEvidenceCommands.Upload(contractId.toString(),
                milestoneId.toString(), auth.getName(), idempotencyKey, request.getContentType(),
                request.getContentLengthLong()), request.getInputStream());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(MilestoneEvidenceAccepted.from(uploaded));
    }

    /** The seller declares (or, within the window, corrects) the loaded weight. 204. */
    @PostMapping(path = "/weigh", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "SELLER: declare the loaded weight of this milestone (correctable for a short window)")
    public ResponseEntity<Void> weigh(
            @PathVariable UUID contractId,
            @PathVariable UUID milestoneId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody String body,
            Authentication auth) {
        requireRole(auth, Set.of("ROLE_SELLER"), "Only the SELLER can declare the weight.");
        // API order: party (403), milestone (404), then the body (400).
        weigh.requireSellerOfMilestone(contractId.toString(), milestoneId.toString(), auth.getName());
        SellerWeighedRequest request = read(body);
        weigh.execute(new MilestoneEvidenceCommands.Weigh(contractId.toString(), milestoneId.toString(),
                auth.getName(), idempotencyKey, request.sellerDeclaredWeight(),
                request.sellerEvidenceFileId().toString(),
                request.deliveryCertificateFileId() == null ? null : request.deliveryCertificateFileId().toString(),
                request.consent().statementVersion(), request.consent().presentedContentHash()));
        return ResponseEntity.noContent().build();
    }

    /** D84: the BF-04 read model; parties of the contract, ARBITRATOR and OPERATOR. */
    @GetMapping
    @Operation(summary = "Party or staff: the milestone with its declaration, evidence and deadlines")
    public ResponseEntity<MilestoneDetailResponse> getMilestone(@PathVariable UUID contractId,
                                                                @PathVariable UUID milestoneId,
                                                                Authentication auth) {
        return ResponseEntity.ok(MilestoneDetailResponse.from(detail.execute(contractId.toString(),
                milestoneId.toString(), auth.getName(), roles(auth))));
    }

    /** D84: streams a READY evidence file of this milestone; Cache-Control private, no-store. */
    @GetMapping("/evidence/{fileId}")
    @Operation(summary = "Party or ARBITRATOR: the bytes of an evidence file of this milestone")
    public void readEvidence(@PathVariable UUID contractId, @PathVariable UUID milestoneId,
                             @PathVariable UUID fileId, Authentication auth, HttpServletResponse response)
            throws IOException {
        EvidenceFileStore.FileContent content = readEvidence.execute(contractId.toString(), milestoneId.toString(),
                fileId.toString(), auth.getName(), roles(auth));
        try (InputStream body = content.body()) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(content.contentType());
            if (content.contentLength() >= 0) {
                response.setContentLengthLong(content.contentLength());
            }
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            body.transferTo(response.getOutputStream());
        }
    }

    /** The JWT roles without the ROLE_ prefix. */
    private static Set<String> roles(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).collect(Collectors.toSet());
    }

    private static void requireRole(Authentication auth, Set<String> roles, String message) {
        boolean allowed = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(roles::contains);
        if (!allowed) {
            throw new UnauthorizedContractActionException(message);
        }
    }

    private SellerWeighedRequest read(String body) {
        try {
            return weighReader.readValue(body);
        } catch (JsonProcessingException unreadable) {
            if (unreadable.getCause() instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("Body is not a valid SellerWeighedRequest: "
                    + unreadable.getOriginalMessage(), unreadable);
        }
    }
}
