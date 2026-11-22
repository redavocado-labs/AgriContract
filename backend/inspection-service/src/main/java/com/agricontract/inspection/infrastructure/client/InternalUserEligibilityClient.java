package com.agricontract.inspection.infrastructure.client;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import com.agricontract.inspection.application.exception.EligibilityUnavailableException;
import com.agricontract.inspection.application.port.InspectorEligibilityPort;
import com.agricontract.inspection.domain.model.InspectorEligibility;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import jakarta.annotation.PreDestroy;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

@Component
public class InternalUserEligibilityClient implements InspectorEligibilityPort {
    private static final Logger LOG = LoggerFactory.getLogger(InternalUserEligibilityClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectReader eligiblePoolReader = mapper.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private final HttpClient transport;
    private final ThreadPoolExecutor httpExecutor;
    private final String baseUrl;
    private final String secret;
    private final int deadlineMs;

    public InternalUserEligibilityClient(RestClient.Builder builder,
            @Value("${inspection.user-service.base-url}") String baseUrl,
            @Value("${inspection.user-service.internal-secret}") String secret,
            @Value("${inspection.user-service.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${inspection.user-service.read-timeout-ms}") int readTimeoutMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("SERVICE_INTERNAL_SECRET must be configured");
        }
        this.baseUrl = baseUrl;
        this.secret = secret;
        this.deadlineMs = readTimeoutMs;
        httpExecutor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), task -> {
            var thread = new Thread(task, "inspection-user-http");
            thread.setDaemon(true);
            return thread;
        });
        transport = HttpClient.newBuilder().executor(httpExecutor)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @PreDestroy
    public void close() {
        transport.shutdownNow();
        httpExecutor.shutdownNow();
    }

    @Override
    public InspectorEligibility load(String authenticatedUserId) {
        try {
            JsonNode body = readInspectorProfile(authenticatedUserId);
            if (body == null || !body.isObject() || !body.has("authorizationExpiresAt")) {
                throw new InvalidEligibility("malformed_response");
            }
            // Missing expiry must not be confused with an explicit unlimited license (JSON null).
            var expires = body.get("authorizationExpiresAt");
            Instant expiry = expires.isNull() ? null : Instant.parse(requiredText(body, "authorizationExpiresAt"));
            String userId = requiredText(body, "userId");
            if (!authenticatedUserId.equals(userId)) {
                throw new InvalidEligibility("identity_mismatch");
            }
            return new InspectorEligibility(userId, requiredText(body, "role"),
                    requiredText(body, "verificationStatus"), expiry);
        } catch (InvalidEligibility failure) {
            throw unavailable(failure.category);
        } catch (DateTimeParseException failure) {
            throw unavailable("malformed_response");
        } catch (RuntimeException failure) {
            // Unexpected client failures also fail closed, without mislabeling them as bad profiles.
            throw unavailable("unexpected");
        }
    }

    @Override
    public String loadInspectorEmail(String userId) {
        try {
            JsonNode body = readInspectorProfile(userId);
            if (body == null || !body.isObject() || !userId.equals(requiredText(body, "userId"))
                    || !body.path("emailVerified").isBoolean() || !body.path("emailVerified").booleanValue())
                throw new InvalidEligibility("malformed_response");
            String email = requiredText(body, "email");
            if (email.length() > 254 || !email.matches(
                    "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
                            + "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$"))
                throw new InvalidEligibility("malformed_response");
            return email;
        } catch (InvalidEligibility failure) {
            throw unavailable(failure.category);
        } catch (RuntimeException failure) {
            throw unavailable("malformed_response");
        }
    }

    private JsonNode readInspectorProfile(String userId) {
        var uri = UriComponentsBuilder.fromUriString(baseUrl).pathSegment("internal", "v1", "users", "{id}")
                .buildAndExpand(userId).encode().toUri();
        return readJson(uri, 65536);
    }

    private JsonNode readJson(java.net.URI uri, int maximumBytes) {
        var request = HttpRequest.newBuilder(uri).header("X-Internal-Secret", secret)
                .timeout(Duration.ofMillis(deadlineMs)).GET().build();
        var future = transport.sendAsync(request, response -> new LimitedBodySubscriber(maximumBytes));
        try {
            var response = future.get(deadlineMs, TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new InvalidEligibility("dependency_http");
            var type = response.headers().firstValue("Content-Type").map(MediaType::parseMediaType).orElse(null);
            if (type == null || !(MediaType.APPLICATION_JSON.isCompatibleWith(type)
                    || new MediaType("application", "*+json").isCompatibleWith(type)))
                throw new InvalidEligibility("malformed_response");
            return eligiblePoolReader.readValue(response.body());
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw new InvalidEligibility("transport");
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new InvalidEligibility("transport");
        } catch (ExecutionException failure) {
            throw new InvalidEligibility("transport");
        } catch (java.io.IOException | InvalidMediaTypeException failure) {
            throw new InvalidEligibility("malformed_response");
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private int bytes;
        private Flow.Subscription subscription;
        private LimitedBodySubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            long received = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (received > limit - bytes) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("Response too large"));
                return;
            }
            bytes += (int) received;
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { delegate.onError(error); }
        @Override public void onComplete() { delegate.onComplete(); }
    }

    @Override
    public List<String> loadEligibleInspectors() {
        try {
            JsonNode body = readJson(UriComponentsBuilder.fromUriString(baseUrl)
                    .pathSegment("internal", "v1", "users", "inspectors", "eligible").build().toUri(), 1024 * 1024);
            if (body == null || !body.isArray()) throw new InvalidEligibility("malformed_response");
            var ids = new LinkedHashSet<String>();
            for (JsonNode item : body) {
                if (!item.isObject() || item.size() != 1) throw new InvalidEligibility("malformed_response");
                String id = requiredText(item, "userId");
                String canonical = UUID.fromString(id).toString();
                if (!canonical.equalsIgnoreCase(id) || !ids.add(canonical))
                    throw new InvalidEligibility("malformed_response");
            }
            return List.copyOf(ids);
        } catch (InvalidEligibility failure) {
            throw unavailable(failure.category);
        } catch (RuntimeException failure) {
            throw unavailable("malformed_response");
        }
    }

    private static EligibilityUnavailableException unavailable(String category) {
        // Only bounded, locally defined categories: never pass the exception or remote data to the logger.
        LOG.warn("eligibility_dependency_failure category={}", category);
        return new EligibilityUnavailableException();
    }

    private static final class InvalidEligibility extends RuntimeException {
        private final String category;

        private InvalidEligibility(String category) {
            this.category = category;
        }
    }

    private static String requiredText(JsonNode body, String field) {
        var value = body.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new InvalidEligibility("malformed_response");
        }
        return value.textValue();
    }
}
