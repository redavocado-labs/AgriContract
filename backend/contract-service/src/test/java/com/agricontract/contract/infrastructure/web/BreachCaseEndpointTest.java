package com.agricontract.contract.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agricontract.contract.application.dto.BreachCaseCommands;
import com.agricontract.contract.application.exception.BreachCaseNotFoundException;
import com.agricontract.contract.application.usecase.GetBreachCaseUseCase;
import com.agricontract.contract.application.usecase.ListBreachCasesForArbitrationUseCase;
import com.agricontract.contract.application.usecase.ResolveBreachCaseUseCase;
import com.agricontract.contract.application.usecase.ReviewBreachCaseUseCase;
import com.agricontract.contract.domain.model.breach.DecisionSource;
import com.agricontract.contract.domain.model.consent.SignerRole;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** reviewBreachCase, resolveBreachCase, listBreachCasesForArbitration: roles, check order and the body. */
class BreachCaseEndpointTest {
    private static final UUID CONTRACT = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();

    private final ReviewBreachCaseUseCase review = mock(ReviewBreachCaseUseCase.class);
    private final ResolveBreachCaseUseCase resolve = mock(ResolveBreachCaseUseCase.class);
    private final GetBreachCaseUseCase get = mock(GetBreachCaseUseCase.class);
    private final ListBreachCasesForArbitrationUseCase list = mock(ListBreachCasesForArbitrationUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BreachCaseController controller = new BreachCaseController(review, resolve, get, list, new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void review_isArbitratorOnly_withNoFallback() throws Exception {
        for (String role : List.of("BUYER", "SELLER", "ADMIN", "OPERATOR", "INSPECTOR")) {
            mvc.perform(reviewRequest(role)).andExpect(status().isForbidden());
        }
        verify(review, never()).execute(any());

        mvc.perform(reviewRequest("ARBITRATOR")).andExpect(status().isNoContent());
        ArgumentCaptor<BreachCaseCommands.Review> command = ArgumentCaptor.forClass(BreachCaseCommands.Review.class);
        verify(review).execute(command.capture());
        assertThat(command.getValue().breachCaseId()).isEqualTo(CASE.toString());
        assertThat(command.getValue().actorId()).isEqualTo("user-1");
    }

    @Test
    void resolve_checksRoleThenCase_thenTheBody_andReachesTheUseCase() throws Exception {
        String good = "{\"finalBreachingRole\":null,\"decisionSource\":\"ARBITRATOR\",\"evidenceRefs\":[\"r1\"]}";
        for (String role : List.of("BUYER", "SELLER", "ADMIN", "OPERATOR", "INSPECTOR")) {
            mvc.perform(resolveRequest(role, good)).andExpect(status().isForbidden());
        }
        doThrow(new BreachCaseNotFoundException(CONTRACT.toString(), CASE.toString())).doNothing()
                .when(resolve).requireCaseOfContract(CONTRACT.toString(), CASE.toString());

        mvc.perform(resolveRequest("ARBITRATOR", "{not json")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BREACH_CASE_NOT_FOUND"));
        for (String bad : List.of("{not json", "[]",
                "{\"decisionSource\":\"ARBITRATOR\",\"evidenceRefs\":[]}",
                "{\"finalBreachingRole\":null,\"evidenceRefs\":[]}",
                "{\"finalBreachingRole\":null,\"decisionSource\":\"ARBITRATOR\"}",
                "{\"finalBreachingRole\":\"ADMIN\",\"decisionSource\":\"ARBITRATOR\",\"evidenceRefs\":[]}",
                "{\"finalBreachingRole\":null,\"decisionSource\":\"SYSTEM\",\"evidenceRefs\":[]}",
                "{\"finalBreachingRole\":null,\"decisionSource\":\"ARBITRATOR\",\"evidenceRefs\":[1]}",
                good.replace("}", ",\"extra\":1}"))) {
            mvc.perform(resolveRequest("ARBITRATOR", bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verify(resolve, never()).execute(any());

        mvc.perform(resolveRequest("ARBITRATOR", good)).andExpect(status().isNoContent());
        mvc.perform(resolveRequest("ARBITRATOR",
                        "{\"finalBreachingRole\":\"SELLER\",\"decisionSource\":\"INSPECTOR_L1_5\",\"evidenceRefs\":[]}"))
                .andExpect(status().isNoContent());
        ArgumentCaptor<BreachCaseCommands.Resolve> command = ArgumentCaptor.forClass(BreachCaseCommands.Resolve.class);
        verify(resolve, org.mockito.Mockito.times(2)).execute(command.capture());
        assertThat(command.getAllValues().get(0).finalBreachingRole()).isNull();
        assertThat(command.getAllValues().get(0).decisionSource()).isEqualTo(DecisionSource.ARBITRATOR);
        assertThat(command.getAllValues().get(0).evidenceRefs()).containsExactly("r1");
        assertThat(command.getAllValues().get(1).finalBreachingRole()).isEqualTo(SignerRole.SELLER);
        assertThat(command.getAllValues().get(1).decisionSource()).isEqualTo(DecisionSource.INSPECTOR_L1_5);
    }

    @Test
    void theQueue_isArbitratorOnly_andValidatesItsParameters() throws Exception {
        for (String role : List.of("BUYER", "SELLER", "ADMIN", "OPERATOR", "INSPECTOR")) {
            mvc.perform(get("/api/v1/arbitration/breach-cases").principal(auth(role))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/arbitration/breach-cases").param("status", "DONE").principal(auth("ARBITRATOR")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/arbitration/breach-cases").param("awaitingApproval", "maybe")
                .principal(auth("ARBITRATOR"))).andExpect(status().isBadRequest());
        verify(list, never()).execute(any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static MockHttpServletRequestBuilder reviewRequest(String role) {
        return post("/api/v1/contracts/{c}/breach-cases/{b}/review", CONTRACT, CASE).principal(auth(role))
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private static MockHttpServletRequestBuilder resolveRequest(String role, String body) {
        return post("/api/v1/contracts/{c}/breach-cases/{b}/resolve", CONTRACT, CASE).principal(auth(role))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken("user-1", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
