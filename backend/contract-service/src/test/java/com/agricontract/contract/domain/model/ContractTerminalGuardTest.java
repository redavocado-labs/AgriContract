package com.agricontract.contract.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agricontract.contract.domain.model.vo.ContractId;
import com.agricontract.contract.domain.model.vo.ContractStatus;
import com.agricontract.contract.domain.model.vo.ContractTerms;
import com.agricontract.contract.domain.model.vo.Money;
import com.agricontract.contract.domain.model.vo.Quantity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** D93 (#139): a contract waits ACTIVE for its final decision and commits only when escrow cleared it. */
class ContractTerminalGuardTest {
    private static final String D = "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
    private static final String X = "0f1e2d3c-4b5a-4968-8776-655443322110";
    private static final Instant NOW = Instant.parse("2026-11-24T03:00:00Z");

    @Test
    void awaitTerminal_recordsTheDecision_andTheSameDecisionAgainIsANoOp() {
        Contract contract = contract(ContractStatus.ACTIVE, null, null);

        contract.awaitTerminal(D, ContractStatus.SETTLED);
        contract.awaitTerminal(D.toUpperCase(), ContractStatus.SETTLED);

        assertThat(contract.getPendingTerminalDecisionId()).isEqualTo(D);
        assertThat(contract.getPendingTerminalStatus()).isEqualTo(ContractStatus.SETTLED);
        assertThat(contract.getStatus()).as("still ACTIVE until the lock clears").isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    void awaitTerminal_refusesAnotherDecisionWhileOneIsPending() {
        Contract contract = contract(ContractStatus.ACTIVE, D, ContractStatus.SETTLED);

        assertThatThrownBy(() -> contract.awaitTerminal(X, ContractStatus.SETTLED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(contract.getPendingTerminalDecisionId()).isEqualTo(D);
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = "SETTLED", mode = EnumSource.Mode.EXCLUDE)
    void awaitTerminal_acceptsOnlySettledForNow(ContractStatus target) {
        Contract contract = contract(ContractStatus.ACTIVE, null, null);

        assertThatThrownBy(() -> contract.awaitTerminal(D, target)).isInstanceOf(IllegalArgumentException.class);
        assertThat(contract.getPendingTerminalDecisionId()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void awaitTerminal_onlyOnAnActiveContract(ContractStatus status) {
        Contract contract = contract(status, null, null);

        assertThatThrownBy(() -> contract.awaitTerminal(D, ContractStatus.SETTLED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitTerminal_commitsWhenThePendingDecisionIsAmongTheCleared() {
        Contract contract = contract(ContractStatus.ACTIVE, D, ContractStatus.SETTLED);

        assertThat(contract.commitTerminal(List.of(X, D.toUpperCase()), NOW)).isEqualTo(TerminalCommitResult.COMMITTED);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.SETTLED);
        assertThat(contract.getSettledAt()).isEqualTo(NOW);
        assertThat(contract.getPendingTerminalDecisionId()).as("kept as evidence").isEqualTo(D);
        assertThat(contract.getPendingTerminalStatus()).isEqualTo(ContractStatus.SETTLED);
    }

    @Test
    void commitTerminal_leavesTheContractActiveWhenThePendingDecisionIsNotCleared() {
        Contract contract = contract(ContractStatus.ACTIVE, D, ContractStatus.SETTLED);

        assertThat(contract.commitTerminal(List.of(X), NOW)).isEqualTo(TerminalCommitResult.PENDING_NOT_CLEARED);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(contract.getSettledAt()).isNull();
    }

    @Test
    void commitTerminal_withNothingPending_changesNothing() {
        Contract contract = contract(ContractStatus.ACTIVE, null, null);

        assertThat(contract.commitTerminal(List.of(D), NOW)).isEqualTo(TerminalCommitResult.NOTHING_PENDING);

        assertThat(contract.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(contract.getSettledAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ContractStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void commitTerminal_onAContractThatIsNotActive_changesNothing(ContractStatus status) {
        Contract contract = contract(status, D, ContractStatus.SETTLED);

        assertThat(contract.commitTerminal(List.of(D), NOW)).isEqualTo(TerminalCommitResult.NOT_ACTIVE);

        assertThat(contract.getStatus()).isEqualTo(status);
        assertThat(contract.getSettledAt()).isNull();
    }

    @Test
    void reconstitute_refusesHalfAPendingDecision() {
        assertThatThrownBy(() -> contract(ContractStatus.ACTIVE, D, null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contract(ContractStatus.ACTIVE, null, ContractStatus.SETTLED))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Contract contract(ContractStatus status, String pendingDecisionId, ContractStatus pendingStatus) {
        ContractTerms terms = new ContractTerms(new Quantity(new BigDecimal("1000"), "KG"),
                new Money(new BigDecimal("100000000"), "VND"), LocalDate.of(2027, 1, 15), new BigDecimal("0.10"),
                new BigDecimal("0.10"), null);
        return Contract.reconstitute(new ContractId(UUID.randomUUID().toString()), UUID.randomUUID().toString(),
                "buyer-1", "seller-1", "Coffee", "Buyer Co", "Seller Co", "buyer@example.com", "seller@example.com",
                terms, null, status, Map.of(), 1L, null, pendingDecisionId, pendingStatus, null);
    }
}
