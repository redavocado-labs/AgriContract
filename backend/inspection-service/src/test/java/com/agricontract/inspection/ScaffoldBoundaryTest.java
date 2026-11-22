package com.agricontract.inspection;

import java.sql.SQLException;
import com.agricontract.inspection.application.exception.InspectionAssignmentAccessDeniedException;
import com.agricontract.inspection.application.exception.InspectorNotEligibleException;
import com.agricontract.inspection.application.port.InspectorEligibilityPort;
import com.agricontract.inspection.application.usecase.InspectionAuthorization;
import com.agricontract.inspection.domain.model.InspectorEligibility;
import com.agricontract.inspection.domain.repository.InspectionAssignmentRepository;
import com.agricontract.inspection.infrastructure.security.GatewayAuthenticationFilter;
import com.agricontract.inspection.infrastructure.files.FileServiceClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"SERVICE_INTERNAL_SECRET=test-service-secret", "GATEWAY_INTERNAL_SECRET=test-gateway-secret",
        "inspection.file-service.download-enabled=true",
        "spring.rabbitmq.listener.simple.auto-startup=false", "management.health.rabbit.enabled=false",
        "inspection.assignment.scheduler-enabled=false", "VIETNAM_HOLIDAYS=2026-01-01"})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScaffoldBoundaryTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42")
            .withDatabaseName("inspection_db");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static final String CONTRACT = UUID.randomUUID().toString();
    private static final String MILESTONE = UUID.randomUUID().toString();
    private static final String INSPECTOR_A = UUID.randomUUID().toString();
    private static final String INSPECTOR_B = UUID.randomUUID().toString();
    private static final Instant ASSIGNED = Instant.parse("2026-10-08T01:02:03.123456Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired InspectionAssignmentRepository assignments;
    @Autowired InspectionAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;
    @MockBean InspectorEligibilityPort users;
    @MockBean FileServiceClient files;

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM level1_5_inspection_assignment");
        when(users.load(INSPECTOR_A)).thenReturn(new InspectorEligibility(INSPECTOR_A, "INSPECTOR", "VERIFIED", null));
        when(users.load(INSPECTOR_B)).thenReturn(new InspectorEligibility(INSPECTOR_B, "INSPECTOR", "VERIFIED", null));
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void authorizationKeepsReadOnlyTransactionAroundEligibilityAndAssignmentRead() throws Exception {
        String commission = UUID.randomUUID().toString();
        insert(commission, CONTRACT, MILESTONE, INSPECTOR_A, null);
        when(users.load(INSPECTOR_A)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            return new InspectorEligibility(INSPECTOR_A, "INSPECTOR", "VERIFIED", null);
        });
        assertThat(authorize(commission, INSPECTOR_A, "INSPECTOR")).isEqualTo(200);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void readOnlyAuthorizationJoinsExistingWriteTransactionAndSeesUncommittedAssignment() {
        String commission = UUID.randomUUID().toString();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            insert(commission, CONTRACT, MILESTONE, INSPECTOR_A, null);
            var callerConnection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            when(users.load(INSPECTOR_A)).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
                assertThat(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).isEqualTo(callerConnection);
                return new InspectorEligibility(INSPECTOR_A, "INSPECTOR", "VERIFIED", null);
            });
            try {
                assertThat(authorize(commission, INSPECTOR_A, "INSPECTOR")).isEqualTo(200);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            status.setRollbackOnly();
        });
        assertThat(assignments.findByCommissionId(commission)).isEmpty();
    }

    @Test
    void caughtAuthorizationDenialStillMarksCallerForRollback() {
        String commission = UUID.randomUUID().toString();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            insert(commission, CONTRACT, MILESTONE, INSPECTOR_A, null);
            try {
                assertThat(authorize(commission, INSPECTOR_B, "INSPECTOR")).isEqualTo(403);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(assignments.findByCommissionId(commission)).isEmpty();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void healthIsAvailable() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void clientIdentityHeadersCannotOpenAnInspectionOperation() throws Exception {
        mvc.perform(post("/api/v1/inspections/contract/milestones/milestone/reports")
                        .header("X-User-Id", "forged-inspector")
                        .header("X-User-Role", "INSPECTOR")
                        .header("X-Gateway-Secret", "forged-secret")
                        .header("Idempotency-Key", "test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyApprovedReportControllersAreExposed() {
        assertThat(mappings.getHandlerMethods().values())
                .filteredOn(handler -> handler.getBeanType().getPackageName().startsWith("com.agricontract.inspection"))
                .extracting(handler -> handler.getBeanType().getSimpleName())
                .containsExactlyInAnyOrder("ReportFileUploadController", "ReportSubmissionController");
    }

    @Test
    void authenticatedInspectorWithoutFreshnessCannotSubmit() throws Exception {
        String body = """
                {"reportFileId":"00000000-0000-4000-8000-000000000011","normalizedResult":{"resultSchemaVersion":"1.0",
                "measuredQuantityKg":10,"acceptedQuantityKg":9,"measurementStatus":"COMPLETE",
                "actualQualityMetrics":{"commodity":"COFFEE","type":"ROBUSTA","moisturePercent":12,
                "foreignMatterPercent":0,"blackBrokenBeansPercent":0},"determinedAt":"2026-10-25T02:59:00.000Z"}}
                """;
        mvc.perform(post("/api/v1/inspections/" + CONTRACT + "/milestones/" + MILESTONE + "/reports")
                        .header("X-User-Id", INSPECTOR_A).header("X-User-Role", "INSPECTOR")
                        .header("X-Gateway-Secret", "test-gateway-secret").header("Idempotency-Key", "test")
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void staleAndDuplicateAuthTimeAreRejectedThroughSecurityChain() throws Exception {
        String body = """
                {"reportFileId":"00000000-0000-4000-8000-000000000011","normalizedResult":{"resultSchemaVersion":"1.0",
                "measuredQuantityKg":10,"acceptedQuantityKg":9,"measurementStatus":"COMPLETE",
                "actualQualityMetrics":{"commodity":"COFFEE","type":"ROBUSTA","moisturePercent":12,
                "foreignMatterPercent":0,"blackBrokenBeansPercent":0},"determinedAt":"2026-10-25T02:59:00.000Z"}}
                """;
        String path = "/api/v1/inspections/" + CONTRACT + "/milestones/" + MILESTONE + "/reports";
        var base = post(path).header("X-User-Id", INSPECTOR_A).header("X-User-Role", "INSPECTOR")
                .header("X-Gateway-Secret", "test-gateway-secret").header("Idempotency-Key", "key")
                .contentType("application/json").content(body);
        mvc.perform(base.header("X-User-Auth-Time", Long.toString(Instant.now().minusSeconds(1801).getEpochSecond())))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).header("X-User-Id", INSPECTOR_A).header("X-User-Role", "INSPECTOR")
                .header("X-Gateway-Secret", "test-gateway-secret").header("Idempotency-Key", "key")
                .header("X-User-Auth-Time", Long.toString(Instant.now().getEpochSecond()), Long.toString(Instant.now().getEpochSecond()))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(files);
    }

    @Test
    void trustedFreshInspectorCanSubmitAndReplayThroughSecurityChain() throws Exception {
        String commission = UUID.randomUUID().toString();
        String file = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO level1_5_inspection_assignment (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,report_due_business_days,report_due_at) VALUES (?,?,?,?,?,'test-fixture',1,?)",
                commission, CONTRACT, MILESTONE, INSPECTOR_A, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(86400)));
        jdbc.update("INSERT INTO level1_5_report_file(file_id,commission_id,uploaded_at) VALUES (?,?,?)", file, commission, Timestamp.from(now));
        when(files.getReady(file)).thenReturn(new byte[]{1, 2, 3});
        String body = """
                {"reportFileId":"%s","normalizedResult":{"resultSchemaVersion":"1.0",
                "measuredQuantityKg":10,"acceptedQuantityKg":9,"measurementStatus":"COMPLETE",
                "actualQualityMetrics":{"commodity":"COFFEE","type":"ROBUSTA","moisturePercent":12,
                "foreignMatterPercent":0,"blackBrokenBeansPercent":0},"determinedAt":"2026-10-25T02:59:00.000Z"}}
                """.formatted(file);
        String path = "/api/v1/inspections/" + CONTRACT + "/milestones/" + MILESTONE + "/reports";
        try {
            for (int i = 0; i < 2; i++) mvc.perform(post(path).header("X-User-Id", INSPECTOR_A)
                    .header("X-User-Role", "INSPECTOR").header("X-Gateway-Secret", "test-gateway-secret")
                    .header("X-User-Auth-Time", Long.toString(now.getEpochSecond()))
                    .header("Idempotency-Key", "same-key").contentType("application/json").content(body))
                    .andExpect(status().isCreated());
            verify(files, times(1)).getReady(file);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE l15_commission_id=?", Integer.class, commission)).isEqualTo(1);
        } finally {
            var reports = jdbc.queryForList("SELECT report_id FROM inspection_report WHERE l15_commission_id=?", String.class, commission);
            for (String report : reports) {
                jdbc.update("DELETE FROM event_outbox WHERE aggregate_id=?", report);
                jdbc.update("DELETE FROM level1_5_report_submission_receipt WHERE report_id=?", report);
                jdbc.update("DELETE FROM inspector_signature WHERE report_id=?", report);
                jdbc.update("DELETE FROM inspection_report WHERE report_id=?", report);
            }
            jdbc.update("DELETE FROM level1_5_report_file WHERE file_id=?", file);
            jdbc.update("DELETE FROM level1_5_inspection_assignment WHERE commission_id=?", commission);
        }
    }

    @Test
    void unassociatedAndNotReadyFilesFailClosedThroughSecurityChain() throws Exception {
        String commission = UUID.randomUUID().toString(); String file = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO level1_5_inspection_assignment (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,report_due_business_days,report_due_at) VALUES (?,?,?,?,?,'test-fixture',1,?)",
                commission, CONTRACT, MILESTONE, INSPECTOR_A, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(86400)));
        String body = """
                {"reportFileId":"%s","normalizedResult":{"resultSchemaVersion":"1.0",
                "measuredQuantityKg":10,"acceptedQuantityKg":9,"measurementStatus":"COMPLETE",
                "actualQualityMetrics":{"commodity":"COFFEE","type":"ROBUSTA","moisturePercent":12,
                "foreignMatterPercent":0,"blackBrokenBeansPercent":0},"determinedAt":"2026-10-25T02:59:00.000Z"}}
                """.formatted(file);
        String path = "/api/v1/inspections/" + CONTRACT + "/milestones/" + MILESTONE + "/reports";
        try {
            mvc.perform(post(path).header("X-User-Id", INSPECTOR_B).header("X-User-Role", "INSPECTOR")
                    .header("X-Gateway-Secret", "test-gateway-secret").header("X-User-Auth-Time", Long.toString(now.getEpochSecond()))
                    .header("Idempotency-Key", "foreign").contentType("application/json").content(body))
                    .andExpect(status().isForbidden());
            var request = post(path).header("X-User-Id", INSPECTOR_A).header("X-User-Role", "INSPECTOR")
                    .header("X-Gateway-Secret", "test-gateway-secret").header("X-User-Auth-Time", Long.toString(now.getEpochSecond()))
                    .header("Idempotency-Key", "file-check").contentType("application/json").content(body);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_FILE_NOT_UPLOADED"));
            verify(files, never()).getReady(anyString());
            jdbc.update("INSERT INTO level1_5_report_file(file_id,commission_id,uploaded_at) VALUES (?,?,?)", file, commission, Timestamp.from(now));
            when(files.getReady(file)).thenThrow(new FileServiceClient.NotReady());
            mvc.perform(request).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_FILE_NOT_READY"));
            doThrow(new FileServiceClient.Unavailable("offline")).when(files).getReady(file);
            mvc.perform(request).andExpect(status().isServiceUnavailable());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inspection_report WHERE l15_commission_id=?", Integer.class, commission)).isZero();
        } finally {
            jdbc.update("DELETE FROM level1_5_report_file WHERE file_id=?", file);
            jdbc.update("DELETE FROM level1_5_inspection_assignment WHERE commission_id=?", commission);
        }
    }

    @Test
    void authorizedUploadBindsFileAndReplaysThroughGatewaySecurityContext() throws Exception {
        String commission = UUID.randomUUID().toString(); String file = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO level1_5_inspection_assignment (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,report_due_business_days,report_due_at) VALUES (?,?,?,?,?,'test-fixture',1,?)",
                commission, CONTRACT, MILESTONE, INSPECTOR_A, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(86400)));
        when(files.storeOnBehalfOf(eq(INSPECTOR_A), eq("upload-key"), eq("application/pdf"), eq(3L), any()))
                .thenReturn(new com.agricontract.inspection.application.port.ReportFileStore.StoredFile(file, "PROCESSING"));
        String path = "/api/v1/inspections/" + CONTRACT + "/milestones/" + MILESTONE + "/report-files";
        try {
            for (int i = 0; i < 2; i++) mvc.perform(post(path).header("X-User-Id", INSPECTOR_A)
                    .header("X-User-Role", "INSPECTOR").header("X-Gateway-Secret", "test-gateway-secret")
                    .header("X-Acting-User-Id", INSPECTOR_B).header("Idempotency-Key", "upload-key")
                    .header("Content-Length", "3").contentType("application/pdf").content(new byte[]{1, 2, 3}))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.fileId").value(file));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_report_file WHERE file_id=? AND commission_id=?", Integer.class, file, commission)).isEqualTo(1);
            verify(files, times(2)).storeOnBehalfOf(eq(INSPECTOR_A), eq("upload-key"), eq("application/pdf"), eq(3L), any());
        } finally {
            jdbc.update("DELETE FROM level1_5_report_file WHERE file_id=?", file);
            jdbc.update("DELETE FROM level1_5_inspection_assignment WHERE commission_id=?", commission);
        }
    }

    @Test
    void concurrentUploadReplaysBindOneFileToTheSameAssignment() throws Exception {
        String commission = UUID.randomUUID().toString(); String file = UUID.randomUUID().toString();
        Instant now = Instant.now(); var rendezvous = new CountDownLatch(2);
        jdbc.update("INSERT INTO level1_5_inspection_assignment (commission_id,contract_id,milestone_id,inspector_id,assigned_at,assigned_by_service,report_due_business_days,report_due_at) VALUES (?,?,?,?,?,'test-fixture',1,?)",
                commission, CONTRACT, MILESTONE, INSPECTOR_A, Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.plusSeconds(86400)));
        when(files.storeOnBehalfOf(eq(INSPECTOR_A), eq("concurrent-key"), eq("application/pdf"), eq(3L), any()))
                .thenAnswer(call -> {
                    rendezvous.countDown();
                    if (!rendezvous.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("upload rendezvous timeout");
                    return new com.agricontract.inspection.application.port.ReportFileStore.StoredFile(file, "PROCESSING");
                });
        String path = "/api/v1/inspections/" + CONTRACT + "/milestones/" + MILESTONE + "/report-files";
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> request = () -> mvc.perform(post(path).header("X-User-Id", INSPECTOR_A)
                    .header("X-User-Role", "INSPECTOR").header("X-Gateway-Secret", "test-gateway-secret")
                    .header("Idempotency-Key", "concurrent-key").header("Content-Length", "3")
                    .contentType("application/pdf").content(new byte[]{1, 2, 3})).andReturn().getResponse().getStatus();
            var one = pool.submit(request); var two = pool.submit(request);
            assertThat(one.get(30, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(two.get(30, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_report_file WHERE file_id=? AND commission_id=?", Integer.class, file, commission)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM level1_5_report_file WHERE file_id=?", file);
            jdbc.update("DELETE FROM level1_5_inspection_assignment WHERE commission_id=?", commission);
        }
    }

    @Test
    void migrationRunsOnMySqlAndOwnershipProvenanceRoundTrips() {
        String id = UUID.randomUUID().toString();
        insert(id, CONTRACT, MILESTONE, INSPECTOR_A, null);
        var loaded = assignments.findByCommissionId(id).orElseThrow();
        assertThat(loaded.inspectorId()).isEqualTo(INSPECTOR_A);
        assertThat(loaded.contractId()).isEqualTo(CONTRACT);
        assertThat(loaded.milestoneId()).isEqualTo(MILESTONE);
        assertThat(loaded.assignedAt()).isEqualTo(ASSIGNED);
        assertThat(loaded.endedAt()).isNull();
        assertThat(loaded.assignedByService()).isEqualTo("test-fixture");
        assertThat(loaded.initiatedByActorId()).isNull();
        assertThat(assignments.findActive(CONTRACT, MILESTONE)).contains(loaded);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.0.42");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND unique_constraint_schema <> DATABASE()", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema = DATABASE() AND table_name='level1_5_inspection_assignment'", Integer.class)).isZero();
    }

    @Test
    void activeUniquenessIsEnforcedByDatabase() {
        insert(UUID.randomUUID().toString(), CONTRACT, MILESTONE, INSPECTOR_A, null);
        assertThatThrownBy(() -> insert(UUID.randomUUID().toString(), CONTRACT, MILESTONE, INSPECTOR_B, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        insert(UUID.randomUUID().toString(), CONTRACT, UUID.randomUUID().toString(), INSPECTOR_B, null);
    }

    @Test
    void commissionPrimaryKeyCannotOverwriteInspector() {
        String id = UUID.randomUUID().toString();
        insert(id, CONTRACT, MILESTONE, INSPECTOR_A, null);
        assertThatThrownBy(() -> insert(id, CONTRACT, UUID.randomUUID().toString(), INSPECTOR_B, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(assignments.findByCommissionId(id).orElseThrow().inspectorId()).isEqualTo(INSPECTOR_A);
    }

    @Test
    void historyIsPreservedButCannotAuthorizeAccess() throws Exception {
        String historical = UUID.randomUUID().toString();
        insert(historical, CONTRACT, MILESTONE, INSPECTOR_A, ASSIGNED.plusSeconds(1));
        insert(UUID.randomUUID().toString(), CONTRACT, MILESTONE, INSPECTOR_A, ASSIGNED.plusSeconds(2));
        String current = UUID.randomUUID().toString();
        insert(current, CONTRACT, MILESTONE, INSPECTOR_B, null);
        assertThat(assignments.findActive(CONTRACT, MILESTONE).orElseThrow().commissionId()).isEqualTo(current);
        assertThat(authorize(historical, INSPECTOR_A, "INSPECTOR")).isEqualTo(403);
        assertThat(assignments.findByCommissionId(historical).orElseThrow().inspectorId()).isEqualTo(INSPECTOR_A);
    }

    @Test
    void invalidEndTimeAndMissingInspectorAreRejectedByMySql() {
        assertThatThrownBy(() -> insert(UUID.randomUUID().toString(), CONTRACT, MILESTONE,
                INSPECTOR_A, ASSIGNED.minusSeconds(1)))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("3819").hasMessageContaining("chk_l15_assignment_time");
        assertThatThrownBy(() -> insert(UUID.randomUUID().toString(), CONTRACT, MILESTONE,
                null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentAssignmentInsertsCannotBothWin() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> racingInsert(start, INSPECTOR_A));
            var second = executor.submit(() -> racingInsert(start, INSPECTOR_B));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM level1_5_inspection_assignment", Integer.class)).isEqualTo(1);
    }

    @Test
    void authenticatedEligibleOwnerPassesButOtherInspectorAndMissingCommissionFail() throws Exception {
        String id = UUID.randomUUID().toString();
        insert(id, CONTRACT, MILESTONE, INSPECTOR_A, null);
        assertThat(authorize(id, INSPECTOR_A, "INSPECTOR")).isEqualTo(200);
        assertThat(authorize(id, INSPECTOR_B, "INSPECTOR")).isEqualTo(403);
        assertThat(authorize(UUID.randomUUID().toString(), INSPECTOR_A, "INSPECTOR")).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUYER", "SELLER", "ADMIN", "OPERATOR"})
    void ownershipDoesNotGrantInspectorRole(String role) throws Exception {
        String id = UUID.randomUUID().toString();
        insert(id, CONTRACT, MILESTONE, INSPECTOR_A, null);
        assertThat(authorize(id, INSPECTOR_A, role)).isEqualTo(403);
        verifyNoInteractions(users);
    }

    @Test
    void ownerWhoseKycIsNoLongerVerifiedIsDenied() throws Exception {
        String id = UUID.randomUUID().toString();
        insert(id, CONTRACT, MILESTONE, INSPECTOR_A, null);
        when(users.load(INSPECTOR_A)).thenReturn(new InspectorEligibility(INSPECTOR_A, "INSPECTOR", "REJECTED", null));
        assertThat(authorize(id, INSPECTOR_A, "INSPECTOR")).isEqualTo(403);
    }

    private int racingInsert(CountDownLatch start, String inspector) throws Exception {
        start.await();
        try {
            insert(UUID.randomUUID().toString(), CONTRACT, MILESTONE, inspector, null);
            return 1;
        } catch (DataIntegrityViolationException expected) {
            return 0;
        }
    }

    // Test fixture only: there is deliberately no production assignment creation API/repository method.
    private void insert(String id, String contract, String milestone, String inspector, Instant endedAt) {
        jdbc.update("INSERT INTO level1_5_inspection_assignment "
                        + "(commission_id, contract_id, milestone_id, inspector_id, assigned_at, ended_at, assigned_by_service, "
                        + "report_due_business_days, report_due_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, contract, milestone, inspector,
                Timestamp.from(ASSIGNED), endedAt == null ? null : Timestamp.from(endedAt), "test-fixture",
                1, Timestamp.from(ASSIGNED.plusSeconds(86400)));
    }

    // Exercise filter -> authenticated context -> eligibility -> real MySQL ownership, without a test-only endpoint.
    private int authorize(String commission, String actor, String role) throws Exception {
        var request = new MockHttpServletRequest();
        request.setServletPath("/api/v1/inspections/" + CONTRACT + "/milestones/" + MILESTONE + "/reports");
        request.addHeader("X-Gateway-Secret", "test-gateway-secret");
        request.addHeader("X-User-Id", actor);
        request.addHeader("X-User-Role", role);
        var response = new MockHttpServletResponse();
        new GatewayAuthenticationFilter("test-gateway-secret").doFilter(request, response, (req, res) -> {
            try {
                authorization.requireCommission(commission);
            } catch (AccessDeniedException | InspectorNotEligibleException | InspectionAssignmentAccessDeniedException denied) {
                response.setStatus(403);
            }
        });
        return response.getStatus();
    }
}
