package com.agricontract.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.agricontract.gateway.filter.RequestBodyLimitGatewayFilterFactory;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The routes of application.yml (D71): only POST .../milestones/{mid}/evidence lifts the body cap to 10 MiB; every
 * other route, the rest of contract-service included, keeps the 1 MiB default.
 */
@SpringBootTest
class EvidenceUploadRouteTest {

    @Autowired
    RouteLocator routes;

    @Test
    void theEvidenceUpload_isTheOnlyRouteWithA10MiBCap() {
        Route upload = match(HttpMethod.POST, "/api/v1/contracts/c1/milestones/m1/evidence");
        assertThat(upload.getId()).isEqualTo("contract-milestone-evidence-upload");
        assertThat(upload.getUri().getPort()).isEqualTo(8083);
        assertThat(Long.parseLong(upload.getMetadata().get(RequestBodyLimitGatewayFilterFactory.ROUTE_LIMIT_METADATA)
                .toString())).isEqualTo(10_485_760L);

        for (Route other : new Route[] {
                match(HttpMethod.GET, "/api/v1/contracts/c1/milestones/m1/evidence"),
                match(HttpMethod.POST, "/api/v1/contracts/c1/milestones/m1/weigh"),
                match(HttpMethod.POST, "/api/v1/contracts")}) {
            assertThat(other.getId()).isEqualTo("contract-service");
        }
        assertThat(routes.getRoutes().filter(r -> r.getMetadata()
                        .containsKey(RequestBodyLimitGatewayFilterFactory.ROUTE_LIMIT_METADATA))
                .map(Route::getId).collectList().block())
                .containsExactly("contract-milestone-evidence-upload");
    }

    private Route match(HttpMethod method, String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, path).build());
        return routes.getRoutes()
                .sort(Comparator.comparingInt(Route::getOrder))
                .concatMap(route -> Mono.from(route.getPredicate().apply(exchange))
                        .filter(Boolean::booleanValue).map(matched -> route))
                .next()
                .block();
    }
}
