package com.agricontract.gateway.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String VALID = "0b9d6f3e-5c2a-4e8b-9f1d-7a6c5b4e3d2f";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void withoutHeader_generatesUuid_forDownstreamAndResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/listings"));

        ServerWebExchange forwarded = run(exchange);

        String generated = forwarded.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
        assertThat(generated).matches(UUID_REGEX);
        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdFilter.HEADER)).containsExactly(generated);
    }

    @Test
    void withValidHeader_keepsIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/listings")
                .header(CorrelationIdFilter.HEADER, VALID));

        ServerWebExchange forwarded = run(exchange);

        assertThat(forwarded.getRequest().getHeaders().get(CorrelationIdFilter.HEADER)).containsExactly(VALID);
        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdFilter.HEADER)).containsExactly(VALID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"keep-me", "1-1-1-1-1", " " + VALID, VALID + "0", "0b9d6f3e5c2a4e8b9f1d7a6c5b4e3d2f"})
    void resolve_replacesAnythingButACanonicalUuid(String incoming) {
        assertThat(CorrelationIdFilter.resolve(incoming)).matches(UUID_REGEX).isNotEqualTo(incoming);
    }

    @Test
    void resolve_acceptsUpperCaseUuid() {
        String upper = VALID.toUpperCase();
        assertThat(CorrelationIdFilter.resolve(upper)).isEqualTo(upper);
    }

    @Test
    void responseHeader_overridesValueSetDownstream() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/listings")
                .header(CorrelationIdFilter.HEADER, VALID));

        filter.filter(exchange, ex -> {
            ex.getResponse().getHeaders().set(CorrelationIdFilter.HEADER, "downstream-value");
            return ex.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getHeaders().get(CorrelationIdFilter.HEADER)).containsExactly(VALID);
    }

    private ServerWebExchange run(MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            forwarded.set(ex);
            return ex.getResponse().setComplete();
        }).block();
        assertThat(forwarded.get()).isNotNull();
        return forwarded.get();
    }
}
