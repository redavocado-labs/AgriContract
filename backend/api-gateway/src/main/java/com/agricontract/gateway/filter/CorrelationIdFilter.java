package com.agricontract.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request one X-Correlation-Id (gateway design 2 and 5.4): a valid UUID from the client is kept,
 * anything else is replaced by a fresh UUID. The id goes to the downstream request and back on the response.
 *
 * A WebFilter ahead of Spring Security rather than a GlobalFilter, so responses the gateway answers itself
 * (401, 403, 404, 429, 504) carry the id too. The response header is set just before commit, which also
 * overrides whatever a downstream service echoed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter implements WebFilter {

    public static final String HEADER = "X-Correlation-Id";

    // Canonical 8-4-4-4-12 form only: UUID.fromString also accepts short forms such as "1-1-1-1-1".
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));

        exchange.getResponse().beforeCommit(() -> {
            exchange.getResponse().getHeaders().set(HEADER, correlationId);
            return Mono.empty();
        });

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, correlationId))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    static String resolve(String incoming) {
        if (incoming != null && UUID_PATTERN.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
