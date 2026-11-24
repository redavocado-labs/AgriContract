package com.agricontract.contract.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agricontract.contract.application.dto.RetryRemedyLegsCommand;
import com.agricontract.contract.application.exception.ContractNotFoundException;
import com.agricontract.contract.application.usecase.RetryRemedyLegsUseCase;
import com.agricontract.contract.domain.exception.ContractConflictException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** D93: only OPERATOR retries remedy legs; 403 before 404 before 409. */
class RemedyRecoveryControllerTest {
    private static final String OPERATOR = "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a";

    private final RetryRemedyLegsUseCase useCase = mock(RetryRemedyLegsUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RemedyRecoveryController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void operatorRetry_is204_withTheOperatorAsActor() throws Exception {
        UUID contractId = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        mvc.perform(post("/api/v1/contracts/{id}/remedy/retry-legs", contractId)
                        .header("Idempotency-Key", key.toString()).principal(as("OPERATOR")))
                .andExpect(status().isNoContent());

        verify(useCase).execute(new RetryRemedyLegsCommand(contractId.toString(), OPERATOR, key));
    }

    @Test
    void partiesAndOtherStaffCannotRetry_andTheKeyIsRequired() throws Exception {
        for (String role : List.of("BUYER", "SELLER", "ADMIN", "ARBITRATOR", "INSPECTOR")) {
            mvc.perform(post("/api/v1/contracts/{id}/remedy/retry-legs", UUID.randomUUID())
                            .header("Idempotency-Key", UUID.randomUUID().toString()).principal(as(role)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/contracts/{id}/remedy/retry-legs", UUID.randomUUID())
                        .principal(as("OPERATOR")))
                .andExpect(status().isBadRequest());
        verify(useCase, never()).execute(any());
    }

    @Test
    void unknownContract_is404_andAContractNotActive_is409() throws Exception {
        UUID unknown = UUID.randomUUID();
        doThrow(new ContractNotFoundException(unknown.toString())).when(useCase)
                .execute(new RetryRemedyLegsCommand(unknown.toString(), OPERATOR, new UUID(0, 1)));
        mvc.perform(post("/api/v1/contracts/{id}/remedy/retry-legs", unknown)
                        .header("Idempotency-Key", new UUID(0, 1).toString()).principal(as("OPERATOR")))
                .andExpect(status().isNotFound());

        UUID signed = UUID.randomUUID();
        doThrow(new ContractConflictException(ContractConflictException.REMEDY_RETRY_NOT_ALLOWED, "not ACTIVE"))
                .when(useCase).execute(new RetryRemedyLegsCommand(signed.toString(), OPERATOR, new UUID(0, 2)));
        mvc.perform(post("/api/v1/contracts/{id}/remedy/retry-legs", signed)
                        .header("Idempotency-Key", new UUID(0, 2).toString()).principal(as("OPERATOR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REMEDY_RETRY_NOT_ALLOWED"));
    }

    private static UsernamePasswordAuthenticationToken as(String role) {
        return new UsernamePasswordAuthenticationToken(OPERATOR, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
