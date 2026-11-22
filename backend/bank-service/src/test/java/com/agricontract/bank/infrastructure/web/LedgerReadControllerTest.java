package com.agricontract.bank.infrastructure.web;

import com.agricontract.bank.application.dto.LedgerQuery;
import com.agricontract.bank.application.usecase.QueryLedgerEntriesUseCase;
import com.agricontract.bank.application.usecase.QueryLedgerEntriesUseCaseImpl;
import com.agricontract.bank.domain.model.EntryType;
import com.agricontract.bank.domain.model.FundType;
import com.agricontract.bank.domain.model.LedgerEntry;
import com.agricontract.bank.domain.model.Money;
import com.agricontract.bank.domain.repository.LedgerEntryFilter;
import com.agricontract.bank.domain.repository.LedgerEntryRepository;
import com.agricontract.bank.infrastructure.contract.FrozenSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerReadControllerTest {

    static final String SECRET = "test-only-internal-secret";
    static final String URL = "/internal/v1/bank/ledger";
    static final ObjectMapper JSON =
            new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    final List<LedgerEntryFilter> filters = new ArrayList<>();
    final List<LedgerEntry> stored = new ArrayList<>();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        LedgerEntryRepository repository = new LedgerEntryRepository() {
            public void append(LedgerEntry entry) { throw new UnsupportedOperationException(); }
            public Optional<LedgerEntry> findBySourceEventId(String id) { throw new UnsupportedOperationException(); }
            public Optional<LedgerEntry> findByRemedyLegId(String id) { throw new UnsupportedOperationException(); }
            public List<LedgerEntry> search(LedgerEntryFilter filter) {
                filters.add(filter);
                return stored;
            }
        };
        QueryLedgerEntriesUseCase useCase = new QueryLedgerEntriesUseCaseImpl(repository);
        mvc = MockMvcBuilders.standaloneSetup(new LedgerReadController(useCase))
                .setControllerAdvice(new BankExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new InternalSecretFilter(SECRET, JSON))
                .build();
    }

    @Test
    void missingSecretIsUnauthorized() throws Exception {
        mvc.perform(get(URL).param("contractId", id()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(filters).isEmpty();
    }

    @Test
    void wrongSecretIsUnauthorized() throws Exception {
        mvc.perform(get(URL).param("contractId", id()).header("X-Internal-Secret", SECRET + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void neitherContractNorParticipantIsAValidationError() throws Exception {
        mvc.perform(get(URL).header("X-Internal-Secret", SECRET).param("remedyDecisionId", id()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value(URL));
    }

    @Test
    void fromWithoutToIsAValidationError() throws Exception {
        mvc.perform(get(URL).header("X-Internal-Secret", SECRET).param("contractId", id())
                        .param("from", "2026-11-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void fromAfterToIsAValidationError() throws Exception {
        mvc.perform(get(URL).header("X-Internal-Secret", SECRET).param("contractId", id())
                        .param("from", "2026-11-02T00:00:00Z").param("to", "2026-11-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void malformedUuidOrDateTimeIsAValidationError() throws Exception {
        mvc.perform(get(URL).header("X-Internal-Secret", SECRET).param("contractId", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(get(URL).header("X-Internal-Secret", SECRET).param("contractId", id())
                        .param("from", "yesterday").param("to", "today"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returnsEveryKeyWithNullsAndMoneyAsTwoDecimalString() throws Exception {
        stored.add(LedgerEntry.reconstitute(id(), id(), id(), null, id(), EntryType.LOCK_BUYER_DEPOSIT,
                FundType.BUYER_DEPOSIT, null, null, new Money(new BigDecimal("40000000")),
                Instant.parse("2026-10-27T03:00:00.5Z")));
        String contractId = id();

        MvcResult result = mvc.perform(get(URL).header("X-Internal-Secret", SECRET).param("contractId", contractId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].milestoneId").value((Object) null))
                .andExpect(jsonPath("$[0].remedyLegId").value((Object) null))
                .andExpect(jsonPath("$[0].amount").value("40000000.00"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-10-27T03:00:00.500Z"))
                .andReturn();

        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("entryId", "sourceEventId",
                "contractId", "milestoneId", "userId", "entryType", "fundType", "remedyDecisionId", "remedyLegId",
                "amount", "createdAt");
        assertThat(body.get(0).has("milestoneId")).isTrue();
        FrozenSchemas.assertApiSchema("LedgerEntryList", body);
        assertThat(filters).singleElement().extracting(LedgerEntryFilter::contractId).isEqualTo(contractId);
    }

    @Test
    void passesAllFiltersAndTheHalfOpenWindowToTheRepository() throws Exception {
        String participant = id();
        String decision = id();
        mvc.perform(get(URL).accept(MediaType.APPLICATION_JSON).header("X-Internal-Secret", SECRET)
                        .param("participantId", participant).param("remedyDecisionId", decision)
                        .param("from", "2026-11-01T00:00:00Z").param("to", "2026-11-02T07:00:00+07:00"))
                .andExpect(status().isOk());

        assertThat(filters).containsExactly(new LedgerEntryFilter(null, participant, decision,
                Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z")));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
