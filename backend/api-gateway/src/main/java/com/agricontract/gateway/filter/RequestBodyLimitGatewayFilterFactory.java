package com.agricontract.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Caps the request body at the edge (gateway design 5.2) and answers 413 without forwarding.
 *
 * <p>The stock RequestSize filter only reads Content-Length, so a chunked body would slip past it. Here a
 * declared length is checked up front; a chunked body is buffered up to the cap and forwarded with an exact
 * Content-Length, byte for byte, so a signed body reaches the owning service unchanged.
 *
 * <p>A route may carry its own cap in its metadata ({@value #ROUTE_LIMIT_METADATA}); it then replaces the configured
 * one, so the default filter does not stop a route that legitimately takes larger bodies (the 10 MiB milestone
 * evidence upload, D71). Routes without that metadata keep the configured cap.
 */
@Component
public class RequestBodyLimitGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RequestBodyLimitGatewayFilterFactory.Config> {

    public RequestBodyLimitGatewayFilterFactory() {
        super(Config.class);
    }

    public static final String ROUTE_LIMIT_METADATA = "request-body-limit-bytes";

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("maxBytes");
    }

    @Override
    public GatewayFilter apply(Config config) {
        long maxBytes = config.getMaxBytes();
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("RequestBodyLimit maxBytes must be between 1 and " + Integer.MAX_VALUE);
        }
        return (exchange, chain) -> {
            long limit = effectiveLimit(exchange, maxBytes);
            ServerHttpRequest request = exchange.getRequest();
            HttpHeaders headers = request.getHeaders();
            long declared = headers.getContentLength();
            if (declared > limit) {
                return tooLarge(exchange);
            }
            if (declared >= 0 || !headers.containsKey(HttpHeaders.TRANSFER_ENCODING)) {
                return chain.filter(exchange);
            }
            return DataBufferUtils.join(request.getBody(), (int) limit)
                    .map(body -> exchange.mutate().request(new BufferedBodyRequest(request, body)).build())
                    .defaultIfEmpty(exchange)
                    .flatMap(chain::filter)
                    .onErrorResume(DataBufferLimitException.class, ex -> tooLarge(exchange));
        };
    }

    /** The matched route's own cap when it declares one, the configured cap otherwise. */
    static long effectiveLimit(ServerWebExchange exchange, long configured) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Object value = route == null ? null : route.getMetadata().get(ROUTE_LIMIT_METADATA);
        if (value == null) {
            return configured;
        }
        long limit = value instanceof Number number ? number.longValue() : Long.parseLong(value.toString().strip());
        if (limit <= 0 || limit > Integer.MAX_VALUE) {
            throw new IllegalStateException("Route " + route.getId() + " has an invalid " + ROUTE_LIMIT_METADATA);
        }
        return limit;
    }

    private static Mono<Void> tooLarge(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
        return exchange.getResponse().setComplete();
    }

    private static class BufferedBodyRequest extends ServerHttpRequestDecorator {

        private final DataBuffer body;
        private final HttpHeaders headers;

        BufferedBodyRequest(ServerHttpRequest delegate, DataBuffer body) {
            super(delegate);
            this.body = body;
            HttpHeaders copy = new HttpHeaders();
            copy.addAll(delegate.getHeaders());
            copy.remove(HttpHeaders.TRANSFER_ENCODING);
            copy.setContentLength(body.readableByteCount());
            this.headers = HttpHeaders.readOnlyHttpHeaders(copy);
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            return Flux.just(body);
        }
    }

    public static class Config {

        private long maxBytes;

        public long getMaxBytes() {
            return maxBytes;
        }

        public void setMaxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
        }
    }
}
