package com.agricontract.bank.infrastructure;

import com.agricontract.bank.domain.model.EntryType;
import com.agricontract.bank.domain.model.FundType;
import com.agricontract.bank.domain.model.LedgerEntry;
import com.agricontract.bank.domain.model.Money;
import com.agricontract.bank.domain.repository.LedgerEntryFilter;
import com.agricontract.bank.infrastructure.persistence.JdbcLedgerEntryRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The ledger read query on real MySQL, as the SELECT-only runtime account. */
class LedgerSearchMySqlTest {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.42")
            .withDatabaseName("bank_db").withUsername("bank_migration")
            .withPassword("test-only-migration-password").withEnv("MYSQL_ROOT_HOST", "%");
    static JdbcLedgerEntryRepository repository;

    static final String CONTRACT = id();
    static final String OTHER_CONTRACT = id();
    static final String BUYER = id();
    static final String SELLER = id();
    static final String MILESTONE = id();
    static final String DECISION = id();
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    @BeforeAll
    static void setUp() throws Exception {
        MYSQL.start();
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        admin.execute("CREATE USER 'bank_runtime'@'%' IDENTIFIED BY 'test-only-runtime-password'");
        String sql = Files.readString(Path.of("ops/grants.sql")).replaceAll("(?m)--[^\\r\\n]*", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) admin.execute(statement);
        repository = new JdbcLedgerEntryRepository(new JdbcTemplate(
                new DriverManagerDataSource(MYSQL.getJdbcUrl(), "bank_runtime", "test-only-runtime-password")));

        // Inserted out of order on purpose; two rows share a created_at to exercise the entry_id tiebreak.
        repository.append(entry("00000000-0000-4000-8000-000000000003", CONTRACT, SELLER, EntryType.LOCK_SELLER_DEPOSIT,
                FundType.SELLER_DEPOSIT, null, null, null, T0.plusSeconds(10)));
        repository.append(entry("00000000-0000-4000-8000-000000000002", CONTRACT, BUYER, EntryType.LOCK_BUYER_DEPOSIT,
                FundType.BUYER_DEPOSIT, null, null, null, T0));
        repository.append(entry("00000000-0000-4000-8000-000000000001", CONTRACT, SELLER, EntryType.CONTRACTUAL_PENALTY,
                FundType.MILESTONE_PAYMENT, MILESTONE, DECISION, id(), T0));
        repository.append(entry("00000000-0000-4000-8000-000000000004", OTHER_CONTRACT, BUYER, EntryType.LOCK_BUYER_DEPOSIT,
                FundType.BUYER_DEPOSIT, null, null, null, T0.plusSeconds(20)));
    }

    @Test
    void byContractIsOrderedByCreatedAtThenEntryId() {
        List<LedgerEntry> rows = repository.search(new LedgerEntryFilter(CONTRACT, null, null, null, null));

        assertThat(rows).extracting(LedgerEntry::getEntryId).containsExactly(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000003");
    }

    @Test
    void filtersAreAnded() {
        assertThat(repository.search(new LedgerEntryFilter(CONTRACT, SELLER, null, null, null)))
                .extracting(LedgerEntry::getEntryId).containsExactly(
                        "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000003");
        assertThat(repository.search(new LedgerEntryFilter(CONTRACT, SELLER, DECISION, null, null)))
                .extracting(LedgerEntry::getEntryId).containsExactly("00000000-0000-4000-8000-000000000001");
        assertThat(repository.search(new LedgerEntryFilter(OTHER_CONTRACT, SELLER, null, null, null))).isEmpty();
    }

    @Test
    void byParticipantSpansContracts() {
        assertThat(repository.search(new LedgerEntryFilter(null, BUYER, null, null, null)))
                .extracting(LedgerEntry::getContractId).containsExactly(CONTRACT, OTHER_CONTRACT);
    }

    @Test
    void fromIsInclusiveAndToIsExclusive() {
        List<LedgerEntry> rows = repository.search(
                new LedgerEntryFilter(CONTRACT, null, null, T0, T0.plusSeconds(10)));

        assertThat(rows).extracting(LedgerEntry::getEntryId).containsExactly(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002");
        assertThat(rows.get(0).getAmount().amount()).isEqualByComparingTo("1000000.00");
    }

    private static LedgerEntry entry(String entryId, String contractId, String userId, EntryType type, FundType fund,
                                     String milestoneId, String decisionId, String legId, Instant createdAt) {
        return LedgerEntry.reconstitute(entryId, id(), contractId, milestoneId, userId, type, fund, decisionId, legId,
                new Money(new BigDecimal("1000000.00")), createdAt);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
