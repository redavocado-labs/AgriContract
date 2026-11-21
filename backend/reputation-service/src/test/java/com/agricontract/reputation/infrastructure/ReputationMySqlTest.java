package com.agricontract.reputation.infrastructure;

import com.agricontract.reputation.application.exception.ImmutableFactConflictException;
import com.agricontract.reputation.application.port.LockEntryWriter;
import com.agricontract.reputation.application.port.LockHistoryReader;
import com.agricontract.reputation.application.port.LockEventPublisher;
import com.agricontract.reputation.application.dto.ProcessLockoutCommand;
import com.agricontract.reputation.application.usecase.ProcessLockoutUseCase;
import com.agricontract.reputation.application.usecase.LockEventLifecycle;
import com.agricontract.reputation.domain.RemedyAttribution.Role;
import com.agricontract.reputation.domain.model.*;
import com.agricontract.reputation.domain.repository.FactRepository;
import com.agricontract.reputation.infrastructure.contract.FrozenSchemas;
import com.agricontract.reputation.infrastructure.messaging.ReputationFactConsumer;
import com.agricontract.reputation.infrastructure.messaging.ReputationFactConsumerTest;
import com.agricontract.reputation.infrastructure.persistence.JdbcFactRepository;
import com.agricontract.reputation.infrastructure.persistence.JdbcLockHistoryReader;
import com.agricontract.reputation.infrastructure.persistence.JdbcLockStateStore;
import com.agricontract.reputation.infrastructure.persistence.SpringTransactionRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.agricontract.reputation.domain.repository.FactRepository.AppendResult.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ReputationMySqlTest {
    static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    static final String SECRET = "test-only-gateway-secret";
    static final JdbcTemplate ADMIN = ReputationDatabaseFixture.ADMIN;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        ReputationDatabaseFixture.properties(r);
        r.add("spring.rabbitmq.username", () -> "unused");
        r.add("spring.rabbitmq.password", () -> "unused");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        r.add("gateway.internal-secret", () -> SECRET);
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @Autowired FactRepository facts;
    @Autowired LockEntryWriter lockWriter;
    @Autowired ProcessLockoutUseCase processLockout;
    @Autowired LockHistoryReader lockHistory;
    @Autowired LockEventPublisher lockPublisher;
    @Autowired JdbcTemplate runtime;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ReputationFactConsumer consumer;
    @BeforeEach void clearFixtures() {
        ADMIN.update("DELETE FROM remedy_delivery_fingerprint");
        ADMIN.update("DELETE FROM processed_event");
        ADMIN.update("DELETE FROM remedy_event_identity");
        ADMIN.update("DELETE FROM remedy_lock_evidence");
        ADMIN.update("DELETE FROM lock_event_receipt"); ADMIN.update("DELETE FROM event_outbox");
        ADMIN.update("DELETE FROM user_lock_state");
        ADMIN.update("DELETE FROM lock_override_event"); ADMIN.update("DELETE FROM lock_entry");
        ADMIN.update("DELETE FROM dispute_outcome_fact"); ADMIN.update("DELETE FROM completed_contract_fact");
    }
    static UUID id() { return UUID.randomUUID(); }
    static ProcessLockoutCommand remedy(UUID source, UUID decision, UUID buyer, UUID seller, Role role,
                                         String reason, boolean eligible, int settled, Instant decided) {
        return new ProcessLockoutCommand(source, decision, id(), buyer, seller, role, reason, eligible,
                settled, decided, id().toString());
    }
    @Test void productionUseCasePersistsSellerAndDeduplicatesDecision() throws Exception {
        UUID buyer = id(), seller = id();
        Instant decided = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var command = remedy(id(), id(), buyer, seller, Role.SELLER, "QUALITY_BELOW_COMMITTED", true, 0, decided);
        assertThat(processLockout.execute(command)).isTrue();
        assertThat(processLockout.execute(command)).isFalse();
        var anotherEvent = new ProcessLockoutCommand(id(), command.remedyDecisionId(), command.contractId(),
                buyer, seller, command.finalBreachingRole(), command.breachReasonCode(), true, 0, decided, command.correlationId());
        assertThat(processLockout.execute(anotherEvent)).isFalse();
        var reusedSource = new ProcessLockoutCommand(anotherEvent.sourceEventId(), id(), command.contractId(),
                buyer, seller, command.finalBreachingRole(), command.breachReasonCode(), true, 0, decided, command.correlationId());
        assertThatThrownBy(() -> processLockout.execute(reusedSource)).isInstanceOf(ImmutableFactConflictException.class);
        var conflicting = new ProcessLockoutCommand(id(), command.remedyDecisionId(), command.contractId(),
                buyer, seller, command.finalBreachingRole(), "SHORTFALL", true, 0, decided, command.correlationId());
        assertThatThrownBy(() -> processLockout.execute(conflicting)).isInstanceOf(ImmutableFactConflictException.class);
        var row = runtime.queryForMap("SELECT * FROM lock_entry");
        assertThat(row.get("user_id")).isEqualTo(seller.toString());
        assertThat(row.get("penalized_role")).isEqualTo("SELLER");
        assertThat(((Number) row.get("base_days")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("lock_duration_days")).intValue()).isEqualTo(3);
        assertThat((BigDecimal) row.get("zero_progress_multiplier")).isEqualByComparingTo("1");
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM lock_entry", Long.class)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT lock_revision FROM user_lock_state WHERE user_id=?", Long.class, seller.toString())).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isEqualTo(1);
        FrozenSchemas.assertEvent("reputationLocked", json.readTree(runtime.queryForObject("SELECT envelope FROM event_outbox", String.class)));
    }
    @Test void productionUseCaseHandlesBuyerIneligibleAndExpired() {
        UUID buyer = id(), seller = id();
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        assertThat(processLockout.execute(remedy(id(), id(), buyer, seller, Role.BUYER, "FUNDING_FAILURE", false, 0, old))).isFalse();
        assertThat(processLockout.execute(remedy(id(), id(), buyer, seller, null, null, false, 0, old))).isFalse();
        assertThat(processLockout.execute(remedy(id(), id(), buyer, seller, null, "FUNDING_FAILURE", true, 0, old))).isFalse();
        assertThat(processLockout.execute(remedy(id(), id(), buyer, seller, Role.BUYER, "FUNDING_FAILURE", true, 0, old))).isTrue();
        var row = runtime.queryForMap("SELECT * FROM lock_entry");
        assertThat(row.get("user_id")).isEqualTo(buyer.toString());
        assertThat(((Number) row.get("lock_duration_days")).intValue()).isEqualTo(45);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM user_lock_state", Long.class)).isZero();
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isZero();
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM lock_event_receipt", Long.class)).isZero();
    }
    @Test void productionUseCaseKeepsExistingProjectionForExpiredDecision() {
        UUID buyer = id(), seller = id();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        assertThat(processLockout.execute(remedy(id(), id(), buyer, seller, Role.SELLER, "SHORTFALL", true, 0, now))).isTrue();
        var before = runtime.queryForMap("SELECT * FROM user_lock_state WHERE user_id=?", seller.toString());
        assertThat(processLockout.execute(remedy(id(), id(), buyer, seller, Role.SELLER, "SHORTFALL", true, 0,
                Instant.parse("2020-01-01T00:00:00Z")))).isTrue();
        assertThat(runtime.queryForMap("SELECT * FROM user_lock_state WHERE user_id=?", seller.toString())).isEqualTo(before);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM lock_entry", Long.class)).isEqualTo(2);
    }
    @Test void productionUseCasePreservesExpiryMicroseconds() {
        Instant decided = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(123456000);
        var command = remedy(id(), id(), id(), id(), Role.SELLER, "SHORTFALL", true, 0, decided);
        assertThat(processLockout.execute(command)).isTrue();
        LocalDateTime expected = LocalDateTime.ofInstant(decided.plusSeconds(3 * 86400L), ZoneOffset.UTC);
        assertThat(runtime.queryForObject("SELECT locked_until FROM lock_entry", LocalDateTime.class)).isEqualTo(expected);
        assertThat(runtime.queryForObject("SELECT effective_locked_until FROM user_lock_state", LocalDateTime.class)).isEqualTo(expected);
    }
    @Test void productionUseCasePersistsTrackRecordAndCeilSnapshot() {
        UUID seller = id();
        Instant decided = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        for (int i = 0; i < 5; i++) {
            ADMIN.update("INSERT INTO completed_contract_fact VALUES (?,?,?,?,?)", id().toString(), id().toString(),
                    seller.toString(), LocalDateTime.ofInstant(decided.minusSeconds(60 + i), ZoneOffset.UTC), id().toString());
        }
        assertThat(processLockout.execute(remedy(id(), id(), id(), seller, Role.SELLER, "SHORTFALL", true, 0, decided))).isTrue();
        var row = runtime.queryForMap("SELECT * FROM lock_entry");
        assertThat((BigDecimal) row.get("track_record_multiplier")).isEqualByComparingTo("0.7");
        assertThat(((Number) row.get("lock_duration_days")).intValue()).isEqualTo(3);
    }
    @Test void productionUseCasePublishesEffectiveMaximumForOverlappingLocks() throws Exception {
        UUID buyer = id(), seller = id();
        Instant decided = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(2);
        assertThat(processLockout.execute(remedy(id(), id(), buyer, seller, Role.SELLER, "SIDE_SELLING", true, 0, decided))).isTrue();
        assertThat(processLockout.execute(remedy(id(), id(), buyer, seller, Role.SELLER, "SHORTFALL", true, 1, decided.plusSeconds(1)))).isTrue();
        var envelopes = runtime.queryForList("SELECT envelope FROM event_outbox ORDER BY id", String.class);
        assertThat(envelopes).hasSize(2);
        var first = json.readTree(envelopes.get(0));
        var second = json.readTree(envelopes.get(1));
        FrozenSchemas.assertEvent("reputationLocked", second);
        assertThat(second.path("payload").path("lockedUntil").asText())
                .isEqualTo(first.path("payload").path("lockedUntil").asText());
    }
    @Test void productionUseCaseRollsBackWhenOutboxPublisherFails() {
        LockEventPublisher failsAfterWrite = (user, locked, until, reason, revision, now, correlation, cause) -> {
            lockPublisher.publish(user, locked, until, reason, revision, now, correlation, cause);
            throw new IllegalStateException("injected after outbox insert");
        };
        var boundary = new LockEventLifecycle(new SpringTransactionRunner(transactions),
                new JdbcLockStateStore(runtime), failsAfterWrite);
        var useCase = new ProcessLockoutUseCase(boundary, lockHistory, lockWriter);
        var command = remedy(id(), id(), id(), id(), Role.BUYER, "FUNDING_FAILURE", true, 1, Instant.now());
        assertThatThrownBy(() -> useCase.execute(command)).isInstanceOf(IllegalStateException.class);
        for (String table : List.of("remedy_event_identity", "remedy_lock_evidence", "lock_entry", "user_lock_state", "lock_event_receipt", "event_outbox")) {
            assertThat(runtime.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).as(table).isZero();
        }
        assertThat(processLockout.execute(command)).isTrue();
    }
    @Test void productionUseCaseSerializesDuplicateDeliveries() throws Exception {
        var command = remedy(id(), id(), id(), id(), Role.BUYER, "FUNDING_FAILURE", true, 1, Instant.now());
        assertThat(race(() -> processLockout.execute(command), () -> processLockout.execute(command)))
                .containsExactlyInAnyOrder(true, false);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM lock_entry", Long.class)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT lock_revision FROM user_lock_state", Long.class)).isEqualTo(1);
    }
    @Test void productionUseCaseSerializesHistoryAcrossSameUserDecisions() throws Exception {
        UUID buyer = id(), seller = id();
        Instant earlier = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(2);
        var first = remedy(id(), id(), buyer, seller, Role.SELLER, "SHORTFALL", true, 1, earlier);
        var second = remedy(id(), id(), buyer, seller, Role.SELLER, "SHORTFALL", true, 1, earlier.plusSeconds(1));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        LockEventPublisher blockingPublisher = (user, locked, until, reason, revision, now, correlation, cause) -> {
            lockPublisher.publish(user, locked, until, reason, revision, now, correlation, cause);
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        var boundary = new LockEventLifecycle(new SpringTransactionRunner(transactions),
                new JdbcLockStateStore(runtime), blockingPublisher);
        var firstUseCase = new ProcessLockoutUseCase(boundary, lockHistory, lockWriter);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> firstUseCase.execute(first));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var b = pool.submit(() -> processLockout.execute(second));
            try {
                assertThatThrownBy(() -> b.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(b.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(runtime.queryForObject("SELECT repeat_offense_multiplier FROM lock_entry WHERE remedy_decision_id=?",
                BigDecimal.class, first.remedyDecisionId().toString())).isEqualByComparingTo("1");
        assertThat(runtime.queryForObject("SELECT repeat_offense_multiplier FROM lock_entry WHERE remedy_decision_id=?",
                BigDecimal.class, second.remedyDecisionId().toString())).isEqualByComparingTo("2");
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM event_outbox", Long.class)).isEqualTo(2);
    }
    @Test void lockWriterUsesCallerTransactionAndStoresImmutableSnapshot() {
        UUID source = id(), decision = id(), user = id();
        var entry = new LockEntryWriter.Entry(id(), source, decision, id(), user, "SELLER", 3,
                BigDecimal.ONE, new BigDecimal("0.7"), BigDecimal.ONE, 3, NOW, NOW.plusSeconds(3 * 86400L));
        assertThatThrownBy(() -> lockWriter.insert(entry)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            lockWriter.insert(entry);
            throw new IllegalStateException("test rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM lock_entry", Long.class)).isZero();
        tx.executeWithoutResult(status -> lockWriter.insert(entry));
        var row = runtime.queryForMap("SELECT * FROM lock_entry WHERE entry_id=?", entry.entryId().toString());
        assertThat(row.get("source_event_id")).isEqualTo(source.toString());
        assertThat(row.get("remedy_decision_id")).isEqualTo(decision.toString());
        assertThat(row.get("user_id")).isEqualTo(user.toString());
        assertThat(((Number) row.get("base_days")).intValue()).isEqualTo(3);
        assertThat((BigDecimal) row.get("track_record_multiplier")).isEqualByComparingTo("0.7");
        assertThat(((Number) row.get("lock_duration_days")).intValue()).isEqualTo(3);
        assertThat(runtime.queryForObject("SELECT decided_at FROM lock_entry WHERE entry_id=?", LocalDateTime.class,
                entry.entryId().toString())).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> lockWriter.insert(entry)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    static CompletedContractFact completed(UUID buyer) {
        return new CompletedContractFact(id(), buyer, id(), NOW.plusNanos(123456789), id());
    }
    static DisputeOutcomeFact dispute(UUID user) {
        return new DisputeOutcomeFact(id(), id(), user, ResolutionFavors.BUYER, NOW, id());
    }
    @Test void completionReplaySurvivesRepositoryRecreationAndMicrosecondRoundTrip() {
        var f = completed(id());
        assertThat(facts.append(f)).isEqualTo(INSERTED);
        assertThat(facts.append(f)).isEqualTo(DUPLICATE);
        var replay = new CompletedContractFact(f.contractId(), f.buyerId(), f.sellerId(), f.settledAt(), id());
        assertThat(new JdbcFactRepository(runtime, transactions).append(replay)).isEqualTo(DUPLICATE);
        assertThat(runtime.queryForObject("SELECT source_event_id FROM completed_contract_fact", String.class))
                .isEqualTo(f.sourceEventId().toString());
        assertThat(runtime.queryForObject("SELECT settled_at FROM completed_contract_fact", LocalDateTime.class))
                .isEqualTo(LocalDateTime.ofInstant(f.settledAt(), ZoneOffset.UTC));
    }
    @Test void disputeReplayAndConflictNeverOverwriteHistory() {
        var f = dispute(id());
        assertThat(facts.append(f)).isEqualTo(INSERTED);
        assertThat(facts.append(f)).isEqualTo(DUPLICATE);
        assertThat(facts.append(new DisputeOutcomeFact(f.milestoneId(), f.contractId(), f.flaggedByUserId(),
                f.resolutionFavors(), f.resolvedAt(), id()))).isEqualTo(DUPLICATE);
        assertThatThrownBy(() -> facts.append(new DisputeOutcomeFact(f.milestoneId(), f.contractId(), f.flaggedByUserId(),
                ResolutionFavors.SELLER, f.resolvedAt(), id()))).isInstanceOf(ImmutableFactConflictException.class);
        assertThatThrownBy(() -> facts.append(new DisputeOutcomeFact(id(), id(), id(),
                ResolutionFavors.BUYER, NOW, f.sourceEventId()))).isInstanceOf(ImmutableFactConflictException.class);
        assertThat(runtime.queryForObject("SELECT resolution_favors FROM dispute_outcome_fact", String.class)).isEqualTo("BUYER");
    }
    @Test void businessAndSourceCollisionsAreNotBlindlyIgnored() {
        var a = completed(id()); var b = completed(id()); facts.append(a); facts.append(b);
        for (var conflict : List.of(
                new CompletedContractFact(a.contractId(), id(), a.sellerId(), a.settledAt(), id()),
                new CompletedContractFact(id(), a.buyerId(), a.sellerId(), a.settledAt(), a.sourceEventId()),
                new CompletedContractFact(a.contractId(), a.buyerId(), a.sellerId(), a.settledAt(), b.sourceEventId()))) {
            assertThatThrownBy(() -> facts.append(conflict)).isInstanceOf(ImmutableFactConflictException.class);
        }
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM completed_contract_fact", Integer.class)).isEqualTo(2);
    }
    @Test void simultaneousIdenticalDeliveriesCommitOneRow() throws Exception {
        var f = completed(id());
        assertThat(race(() -> facts.append(f), () -> facts.append(f))).containsExactlyInAnyOrder(INSERTED, DUPLICATE);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM completed_contract_fact", Integer.class)).isEqualTo(1);
    }
    @Test void simultaneousDisputeDeliveriesCommitOneRow() throws Exception {
        var f = dispute(id());
        assertThat(race(() -> facts.append(f), () -> facts.append(f))).containsExactlyInAnyOrder(INSERTED, DUPLICATE);
    }
    @Test void simultaneousConflictingDeliveriesDoNotMutateWinner() throws Exception {
        var a = completed(id());
        var b = new CompletedContractFact(a.contractId(), id(), a.sellerId(), a.settledAt(), id());
        assertThat(race(() -> appendOrConflict(a), () -> appendOrConflict(b)))
                .containsExactlyInAnyOrder("INSERTED", "CONFLICT");
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM completed_contract_fact", Integer.class)).isEqualTo(1);
    }
    String appendOrConflict(CompletedContractFact f) {
        try { return facts.append(f).name(); } catch (ImmutableFactConflictException e) { return "CONFLICT"; }
    }
    static <T> List<T> race(Callable<T> a, Callable<T> b) throws Exception {
        var ready = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<T> first = pool.submit(() -> { ready.await(10, TimeUnit.SECONDS); return a.call(); });
            Future<T> second = pool.submit(() -> { ready.await(10, TimeUnit.SECONDS); return b.call(); });
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"completed_contract_fact", "dispute_outcome_fact", "lock_entry", "lock_override_event", "remedy_lock_evidence", "remedy_event_identity", "processed_event", "remedy_delivery_fingerprint"})
    void runtimeCannotUpdateDeleteOrChangeProtectedTables(String table) {
        String key = switch (table) {
            case "completed_contract_fact" -> "contract_id";
            case "dispute_outcome_fact" -> "milestone_id";
            case "lock_entry" -> "entry_id";
            case "remedy_lock_evidence" -> "remedy_decision_id";
            case "remedy_event_identity" -> "source_event_id";
            default -> "event_id";
        };
        for (String sql : List.of("UPDATE " + table + " SET " + key + " = " + key,
                "DELETE FROM " + table, "TRUNCATE TABLE " + table,
                "ALTER TABLE " + table + " ADD COLUMN forbidden INT", "DROP TABLE " + table)) {
            assertPermissionDenied(sql);
        }
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).isZero();
    }
    @Test void runtimeCannotWriteOverridesOrAccessMigrationHistory() {
        for (String sql : List.of("INSERT INTO lock_override_event (event_id) VALUES ('x')", "SELECT * FROM flyway_schema_history")) {
            assertPermissionDenied(sql);
        }
        assertThat(ADMIN.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class)).isEqualTo(7);
        assertThat(runtime.queryForObject("SELECT CURRENT_USER()", String.class)).isEqualTo("reputation_runtime@%");
    }
    @Test void runtimeCanInsertLockButCannotChangeTheLedger() {
        UUID entry = id();
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        runtime.update("""
                INSERT INTO lock_entry (entry_id,source_event_id,remedy_decision_id,contract_id,user_id,penalized_role,
                base_days,repeat_offense_multiplier,track_record_multiplier,zero_progress_multiplier,lock_duration_days,
                locked_until,decided_at) VALUES (?,?,?,?,?,'BUYER',3,1,1,1,3,?,?)
                """, entry.toString(), id().toString(), id().toString(), id().toString(), id().toString(), now.plusDays(3), now);
        assertPermissionDenied("UPDATE lock_entry SET base_days=4");
        assertPermissionDenied("DELETE FROM lock_entry");
    }
    void assertPermissionDenied(String sql) {
        assertThatThrownBy(() -> runtime.execute(sql)).isInstanceOf(DataAccessException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class,
                        failure -> assertThat(failure.getErrorCode()).isIn(1142,1143));
    }
    @Test void databaseRejectsInvalidFactsAndOverrideReferences() {
        assertThatThrownBy(() -> runtime.update("INSERT INTO completed_contract_fact (contract_id) VALUES (?)", id().toString()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtime.update("INSERT INTO dispute_outcome_fact VALUES (?,?,?,?,?,?)",
                id().toString(),id().toString(),id().toString(),"UNKNOWN",LocalDateTime.ofInstant(NOW,ZoneOffset.UTC),id().toString()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> override(id(), id(), id())).isInstanceOf(DataAccessException.class);
        UUID lock = lock(id(), NOW.minusSeconds(100), NOW.plusSeconds(100)); UUID actor=id();
        assertThatThrownBy(() -> override(lock,actor,actor)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> ADMIN.update("INSERT INTO lock_entry SELECT * FROM lock_entry"))
                .isInstanceOf(DataAccessException.class);
    }
    @Test void canonicalEventsReplayThroughConsumerAndRealDatabase() throws Exception {
        var settled = ReputationFactConsumerTest.event("contract.settled");
        var resolved = ReputationFactConsumerTest.event("milestone.dispute_resolved");
        FrozenSchemas.assertEvent("contractSettled", settled);
        FrozenSchemas.assertEvent("milestoneDisputeResolved", resolved);
        consumer.completed(ReputationFactConsumerTest.message(settled));
        consumer.completed(ReputationFactConsumerTest.message(settled));
        consumer.dispute(ReputationFactConsumerTest.message(resolved));
        consumer.dispute(ReputationFactConsumerTest.message(resolved));
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM completed_contract_fact", Integer.class)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM dispute_outcome_fact", Integer.class)).isEqualTo(1);
        assertThat(runtime.queryForObject("SELECT COUNT(*) FROM lock_entry", Integer.class)).isZero();
    }
    @Test void sourceAndRemedyDecisionUniquenessAreIndependentLockConstraints() {
        UUID entry=lock(id(), NOW.minusSeconds(200), NOW.plusSeconds(200));
        String insert = """
                INSERT INTO lock_entry SELECT ?,%s,%s,contract_id,user_id,penalized_role,base_days,
                repeat_offense_multiplier,track_record_multiplier,zero_progress_multiplier,
                lock_duration_days,locked_until,created_at,decided_at FROM lock_entry WHERE entry_id=?
                """;
        assertThatThrownBy(() -> ADMIN.update(insert.formatted("source_event_id", "UUID()"),id().toString(),entry.toString()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> ADMIN.update(insert.formatted("UUID()", "remedy_decision_id"),id().toString(),entry.toString()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
    @Test void d55HistoryUsesExclusiveUpperAndInclusiveCalendarLowerBound() {
        UUID user = id();
        Instant lower = NOW.atOffset(ZoneOffset.UTC).minusMonths(24).toInstant();
        UUID earlyUnlocked = lock(user, lower, NOW.plusSeconds(10));
        override(earlyUnlocked, id(), id());
        lock(user, lower.minusNanos(1000), NOW.plusSeconds(10));
        lock(user, NOW, NOW.plusSeconds(10));
        ADMIN.update("INSERT INTO completed_contract_fact VALUES (?,?,?,?,?)", id().toString(),
                user.toString(), id().toString(), LocalDateTime.ofInstant(NOW.minusNanos(1000), ZoneOffset.UTC), id().toString());
        ADMIN.update("INSERT INTO completed_contract_fact VALUES (?,?,?,?,?)", id().toString(),
                id().toString(), user.toString(), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), id().toString());
        var counts = new JdbcLockHistoryReader(runtime).counts(user, NOW);
        assertThat(counts.completed()).isEqualTo(1);
        assertThat(counts.priorLocks()).isEqualTo(2);
        assertThat(counts.recentLocks()).isEqualTo(1);
    }
    @Test void unlockingOneOfTwoOverlappingLocksDoesNotUnlockTheUser() throws Exception {
        UUID user=id();
        UUID first=lock(user,NOW.minusSeconds(200),NOW.plusSeconds(200));
        UUID second=lock(user,NOW.minusSeconds(100),NOW.plusSeconds(100));
        override(first,id(),id());
        mvc.perform(auth(get(route(user)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true)).andExpect(jsonPath("$.score").value(0));
        override(second,id(),id());
        mvc.perform(auth(get(route(user)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false)).andExpect(jsonPath("$.score").value(0))
                .andExpect(jsonPath("$.lockHistory.length()").value(2));
    }
    @Test void publicProjectionIncludesAllHistoricalLocksWithoutPiiAndMatchesFrozenSchema() throws Exception {
        UUID user=id();
        facts.append(completed(user));
        facts.append(new CompletedContractFact(id(), id(), user, NOW, id()));
        facts.append(new CompletedContractFact(id(), user, user, NOW, id())); // OR counts once.
        facts.append(completed(id())); facts.append(dispute(user)); // Neither affects this score.
        lock(user,NOW.minusSeconds(400),NOW); // exact expiry
        UUID overridden=lock(user,NOW.minusSeconds(300),NOW.plusSeconds(500));
        override(overridden,id(),id());
        lock(user,NOW.minusSeconds(200),NOW.plusSeconds(200));
        lock(id(),NOW.minusSeconds(100),NOW.plusSeconds(300));
        var result=mvc.perform(auth(get(route(user)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(50)).andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.lockHistory.length()").value(3))
                .andExpect(jsonPath("$.lockHistory[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.lockHistory[1].status").value("UNLOCKED_EARLY"))
                .andExpect(jsonPath("$.lockHistory[2].status").value("EXPIRED")).andReturn();
        FrozenSchemas.assertResponse("ReputationPublicSummary", json.readTree(result.getResponse().getContentAsString()));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("reason", "proposedBy", "approvedBy",
                "contractId", "sourceEventId", "email", "phone", "address", "remedyDecisionId");
    }
    @Test void overriddenAndExpiredLocksRemainInScoreButDoNotLockUser() throws Exception {
        UUID user=id(); facts.append(completed(user));
        lock(user,NOW.minusSeconds(200),NOW);
        UUID overridden=lock(user,NOW.minusSeconds(100),NOW.plusSeconds(200)); override(overridden,id(),id());
        mvc.perform(auth(get(route(user)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(33)).andExpect(jsonPath("$.locked").value(false));
    }
    @Test void unknownAndDisputeOnlyUsersGetEmptyHistoryWithoutUserServiceLookup() throws Exception {
        UUID disputeOnly=id(); facts.append(dispute(disputeOnly));
        for (UUID user : List.of(id(), disputeOnly)) {
            var result=mvc.perform(auth(get(route(user)))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").value(user.toString()))
                    .andExpect(jsonPath("$.score").isEmpty()).andExpect(jsonPath("$.locked").value(false))
                    .andExpect(jsonPath("$.lockHistory").isEmpty()).andReturn();
            FrozenSchemas.assertResponse("ReputationPublicSummary",json.readTree(result.getResponse().getContentAsString()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"not-a-uuid","1-1-1-1-1"})
    void invalidTargetReturnsContractError(String target) throws Exception {
        var result=mvc.perform(auth(get("/api/v1/reputation/"+target+"/public-summary")))
                .andExpect(status().isBadRequest()).andReturn();
        FrozenSchemas.assertResponse("ErrorEnvelope",json.readTree(result.getResponse().getContentAsString()));
    }
    @Test void forgedMissingMalformedAndRepeatedTrustHeadersFailClosed() throws Exception {
        String path=route(id());
        for (var request : List.of(get(path),get(path).header("X-User-Id",id()).header("X-User-Role","BUYER"),
                auth(get(path)).header("X-Gateway-Secret","second"),
                get(path).header("X-Gateway-Secret","wrong").header("X-User-Id",id()).header("X-User-Role","BUYER"),
                get(path).header("X-Gateway-Secret",SECRET).header("X-User-Id","1-1-1-1-1").header("X-User-Role","BUYER"),
                get(path).header("X-Gateway-Secret",SECRET).header("X-User-Id",id()).header("X-User-Role","UNKNOWN"),
                auth(get(path)).header("X-User-Id",id()),auth(get(path)).header("X-User-Role","ADMIN"))) {
            var result=mvc.perform(request).andExpect(status().isUnauthorized()).andReturn();
            FrozenSchemas.assertResponse("ErrorEnvelope",json.readTree(result.getResponse().getContentAsString()));
        }
        mvc.perform(auth(get(path))).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isUnauthorized()); // no identity leakage across requests
    }
    @ParameterizedTest @ValueSource(strings={"BUYER","SELLER","INSPECTOR","OPERATOR","ARBITRATOR","ADMIN"})
    void everyRecognizedAuthenticatedRoleCanReadAnotherUsersSummary(String role) throws Exception {
        mvc.perform(get(route(id())).header("X-Gateway-Secret",SECRET).header("X-User-Id",id()).header("X-User-Role",role))
                .andExpect(status().isOk());
    }
    @Test void noPublicMutationEndpointExists() throws Exception {
        mvc.perform(auth(post(route(id())))).andExpect(status().isForbidden());
    }
    static String route(UUID user) { return "/api/v1/reputation/"+user+"/public-summary"; }
    static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("X-Gateway-Secret",SECRET).header("X-User-Id",id()).header("X-User-Role","BUYER");
    }
    static UUID lock(UUID user, Instant created, Instant until) {
        UUID entry=id();
        ADMIN.update("""
                INSERT INTO lock_entry (entry_id,source_event_id,remedy_decision_id,contract_id,user_id,penalized_role,
                base_days,repeat_offense_multiplier,track_record_multiplier,zero_progress_multiplier,lock_duration_days,locked_until,created_at,decided_at)
                VALUES (?,?,?,?,?,'BUYER',30,1.0,1.0,1.0,30,?,?,?)
                """,entry.toString(),id().toString(),id().toString(),id().toString(),user.toString(),
                LocalDateTime.ofInstant(until,ZoneOffset.UTC),LocalDateTime.ofInstant(created,ZoneOffset.UTC),
                LocalDateTime.ofInstant(created,ZoneOffset.UTC));
        return entry;
    }
    static void override(UUID entry, UUID proposer, UUID approver) {
        ADMIN.update("INSERT INTO lock_override_event VALUES (?,?,'UNLOCK_EARLY','private test reason',?,?,?)",
                id().toString(),entry.toString(),proposer.toString(),approver.toString(),LocalDateTime.ofInstant(NOW,ZoneOffset.UTC));
    }
}
