package com.agricontract.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/** The default 1 MiB cap, and a route's own cap from its metadata (D71: the 10 MiB evidence upload). */
class RequestBodyLimitGatewayFilterFactoryTest {
    private static final long ONE_MIB = 1_048_576;
    private static final long TEN_MIB = 10_485_760;

    private AtomicInteger forwarded;
    private GatewayFilterChain chain;
    private GatewayFilter defaultCap;

    @BeforeEach
    void setUp() {
        forwarded = new AtomicInteger();
        chain = exchange -> {
            forwarded.incrementAndGet();
            return Mono.empty();
        };
        RequestBodyLimitGatewayFilterFactory.Config config = new RequestBodyLimitGatewayFilterFactory.Config();
        config.setMaxBytes(ONE_MIB);
        defaultCap = new RequestBodyLimitGatewayFilterFactory().apply(config);
    }

    @Test
    void aRouteWithoutItsOwnCap_keepsTheDefault() {
        Route plain = route(Map.of());

        assertThat(status(call(plain, ONE_MIB))).isNull();
        assertThat(status(call(plain, ONE_MIB + 1))).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(status(call(null, ONE_MIB + 1))).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(forwarded).hasValue(1);
    }

    @Test
    void theEvidenceRoute_takesUpTo10MiB_andNotOneByteMore() {
        Route evidence = route(Map.of(RequestBodyLimitGatewayFilterFactory.ROUTE_LIMIT_METADATA, (int) TEN_MIB));

        assertThat(status(call(evidence, 5 * ONE_MIB))).isNull();
        assertThat(status(call(evidence, TEN_MIB))).isNull();
        assertThat(status(call(evidence, TEN_MIB + 1))).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(forwarded).hasValue(2);
    }

    @Test
    void aCapGivenAsText_isReadToo() {
        Route evidence = route(Map.of(RequestBodyLimitGatewayFilterFactory.ROUTE_LIMIT_METADATA, "10485760"));

        assertThat(status(call(evidence, TEN_MIB))).isNull();
    }

    private MockServerWebExchange call(Route route, long contentLength) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/contracts/c/milestones/m/evidence").contentLength(contentLength).build());
        if (route != null) {
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        }
        defaultCap.filter(exchange, chain).block();
        return exchange;
    }

    private static HttpStatus status(MockServerWebExchange exchange) {
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }

    private static Route route(Map<String, Object> metadata) {
        return Route.async().id("r").uri(URI.create("http://localhost:8083")).predicate(e -> true)
                .metadata(metadata).build();
    }
}
