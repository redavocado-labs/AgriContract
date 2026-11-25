package com.agricontract.inspection;

import com.agricontract.inspection.application.port.InspectorEligibilityPort;
import com.agricontract.inspection.domain.model.InspectorEligibility;
import com.agricontract.inspection.infrastructure.files.FileServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Explicitly run with -Dtest=RealFileServiceIntegrationIT and -Dreal.file.url=<isolated file-service URL>. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"SERVICE_INTERNAL_SECRET=agri143-isolated-secret", "GATEWAY_INTERNAL_SECRET=agri143-gateway-secret",
        "VIETNAM_HOLIDAYS=2026-01-01", "inspection.assignment.scheduler-enabled=false",
        "inspection.dlq-monitor.enabled=false", "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RealFileServiceIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("inspection.file-service.base-url", () -> {
            String url = System.getProperty("real.file.url");
            if (url == null || url.isBlank()) throw new IllegalStateException("real.file.url is required");
            return url;
        });
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileServiceClient files;
    @MockBean InspectorEligibilityPort eligibility;

    private HttpResponse<String> send(String path, String actor, String key, String authTime, String contentType, byte[] bytes) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", actor).header("X-User-Role", "INSPECTOR")
                .header("X-Gateway-Secret", "agri143-gateway-secret").header("Idempotency-Key", key)
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        if (authTime != null) request.header("X-User-Auth-Time", authTime);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void uploadScanSubmitAndRetainThroughActualFileService() throws Exception {
        String contract = UUID.randomUUID().toString(), milestone = UUID.randomUUID().toString();
        String inspector = UUID.randomUUID().toString(), foreign = UUID.randomUUID().toString();
        String commission = UUID.randomUUID().toString();
        Instant now = Instant.now();
        when(eligibility.load(inspector)).thenReturn(new InspectorEligibility(inspector, "INSPECTOR", "VERIFIED", null));
        when(eligibility.load(foreign)).thenReturn(new InspectorEligibility(foreign, "INSPECTOR", "VERIFIED", null));
        jdbc.update("INSERT INTO level1_5_inspection_assignment (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,report_due_business_days,report_due_at,free_cancel_until) VALUES (?,?,?,?,?,'test',1,?,CURRENT_TIMESTAMP(6))",
                commission, contract, milestone, inspector, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(86400)));
        String path = "/api/v1/inspections/" + contract + "/milestones/" + milestone;
        byte[] bytes = "%PDF-1.4\n% AgriContract isolated report fixture\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        String authTime = Long.toString(now.getEpochSecond());
        var uploadResponse = send(path + "/report-files", inspector, "real-upload", null, "application/pdf", bytes);
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        String upload = uploadResponse.body();
        assertThat(new ObjectMapper().readTree(upload).path("status").asText()).isEqualTo("PROCESSING");
        String file = new ObjectMapper().readTree(upload).path("fileId").asText();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_report_file WHERE file_id=? AND commission_id=?", Integer.class, file, commission)).isEqualTo(1);
        Instant deadline = Instant.now().plusSeconds(180);
        while (true) {
            try { assertThat(files.getReady(file)).isEqualTo(bytes); break; }
            catch (FileServiceClient.NotReady notReady) {
                if (Instant.now().isAfter(deadline)) throw notReady;
                Thread.sleep(1000);
            }
        }
        String body = """
                {"reportFileId":"%s","normalizedResult":{"resultSchemaVersion":"1.0",
                "measuredQuantityKg":10,"acceptedQuantityKg":9,"measurementStatus":"COMPLETE",
                "actualQualityMetrics":{"commodity":"COFFEE","type":"ROBUSTA","moisturePercent":12,
                "foreignMatterPercent":0,"blackBrokenBeansPercent":0},"determinedAt":"2026-10-25T02:59:00.000Z"}}
                """.formatted(file);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] eicar = ("X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*")
                .getBytes(StandardCharsets.US_ASCII);
        var infectedUpload = send(path + "/report-files", inspector, "infected-upload", null, "application/pdf", eicar);
        assertThat(infectedUpload.statusCode()).isEqualTo(202);
        String infectedFile = new ObjectMapper().readTree(infectedUpload.body()).path("fileId").asText();
        var nonReady = send(path + "/reports", inspector, "non-ready", authTime, "application/json",
                body.replace(file, infectedFile).getBytes(StandardCharsets.UTF_8));
        assertThat(nonReady.statusCode()).isEqualTo(409);
        assertThat(new ObjectMapper().readTree(nonReady.body()).path("code").asText()).isEqualTo("REPORT_FILE_NOT_READY");
        assertThat(send(path + "/reports", foreign, "foreign", authTime, "application/json", bodyBytes).statusCode()).isEqualTo(403);
        assertThat(send(path + "/reports", inspector, "stale", null, "application/json", bodyBytes).statusCode()).isEqualTo(403);
        var unbound = send(path + "/reports", inspector, "unbound", authTime, "application/json",
                body.replace(file, UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8));
        assertThat(unbound.statusCode()).isEqualTo(400);
        assertThat(new ObjectMapper().readTree(unbound.body()).path("code").asText()).isEqualTo("REPORT_FILE_NOT_UPLOADED");
        for (int i = 0; i < 2; i++) assertThat(send(path + "/reports", inspector, "real-submit", authTime,
                "application/json", bodyBytes).statusCode()).isEqualTo(201);
        var repeated = send(path + "/reports", inspector, "another-key", authTime, "application/json", bodyBytes);
        assertThat(repeated.statusCode()).isEqualTo(409);
        assertThat(new ObjectMapper().readTree(repeated.body()).path("code").asText()).isEqualTo("REPORT_ALREADY_SIGNED");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String report = jdbc.queryForObject("SELECT report_id FROM inspection_report WHERE l15_commission_id=?", String.class, commission);
        assertThat(jdbc.queryForObject("SELECT report_file_hash FROM inspection_report WHERE report_id=?", String.class, report)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT report_hash FROM inspector_signature WHERE report_id=?", String.class, report))
                .isEqualTo(jdbc.queryForObject("SELECT report_hash FROM inspection_report WHERE report_id=?", String.class, report));
        assertThat(jdbc.queryForObject("SELECT ended_at FROM level1_5_inspection_assignment WHERE commission_id=?", Timestamp.class, commission)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_report_submission_receipt WHERE report_id=?", Integer.class, report)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE aggregate_id=? AND event_type='inspection.report_confirmed'", Integer.class, report)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attached_confirmed_at FROM level1_5_report_file WHERE file_id=?", Timestamp.class, file)).isNotNull();
        System.out.printf("REAL_FILE_EVIDENCE fileId=%s inspector=%s commission=%s report=%s sha256=%s%n", file, inspector, commission, report, hash);
    }
}
