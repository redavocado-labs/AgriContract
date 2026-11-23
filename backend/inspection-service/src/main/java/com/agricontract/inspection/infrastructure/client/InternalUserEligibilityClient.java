package com.agricontract.inspection.infrastructure.client;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import com.agricontract.inspection.application.exception.EligibilityUnavailableException;
import com.agricontract.inspection.application.port.InspectorEligibilityPort;
import com.agricontract.inspection.domain.model.InspectorEligibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JettyClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Request;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
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
    private final RestClient client;
    private final HttpClient transport;

    public InternalUserEligibilityClient(RestClient.Builder builder,
            @Value("${inspection.user-service.base-url}") String baseUrl,
            @Value("${inspection.user-service.internal-secret}") String secret,
            @Value("${inspection.user-service.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${inspection.user-service.read-timeout-ms}") int readTimeoutMs) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("SERVICE_INTERNAL_SECRET must be configured");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("User-service timeouts must be positive");
        }
        transport = new HttpClient() {
            @Override public Request newRequest(URI uri) {
                return super.newRequest(uri).timeout(readTimeoutMs, TimeUnit.MILLISECONDS);
            }
        };
        transport.setConnectTimeout(connectTimeoutMs);
        transport.setFollowRedirects(false);
        transport.setMaxConnectionsPerDestination(16);
        transport.setMaxRequestsQueuedPerDestination(128);
        var factory = new JettyClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        client = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Internal-Secret", secret).build();
    }

    @PreDestroy
    public void close() throws Exception {
        transport.stop();
    }

    @Override
    public InspectorEligibility load(String authenticatedUserId) {
        try {
            JsonNode body = client.get().uri("/internal/v1/users/{userId}", authenticatedUserId)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new InvalidEligibility("dependency_http");
                        }
                        try {
                            var contentType = response.getHeaders().getContentType();
                            if (contentType == null || !(MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                                    || new MediaType("application", "*+json").isCompatibleWith(contentType))) {
                                throw new InvalidEligibility("malformed_response");
                            }
                            return eligiblePoolReader.readValue(bounded(response.getBody(), 65536));
                        } catch (JsonProcessingException | InvalidMediaTypeException malformed) {
                            throw new InvalidEligibility("malformed_response");
                        }
                        // Other I/O failures propagate to RestClient's ResourceAccessException.
                    });
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
        } catch (ResourceAccessException failure) {
            throw unavailable("transport");
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
            String canonical = UUID.fromString(userId).toString();
            if (!canonical.equals(userId)) throw new InvalidEligibility("malformed_response");
            JsonNode body = client.get().uri("/internal/v1/users/{userId}", userId)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw new InvalidEligibility("dependency_http");
                        try {
                            var type = response.getHeaders().getContentType();
                            if (type == null || !(MediaType.APPLICATION_JSON.isCompatibleWith(type)
                                    || new MediaType("application", "*+json").isCompatibleWith(type)))
                                throw new InvalidEligibility("malformed_response");
                            return eligiblePoolReader.readValue(bounded(response.getBody(), 65536));
                        } catch (JsonProcessingException | InvalidMediaTypeException malformed) {
                            throw new InvalidEligibility("malformed_response");
                        }
                    });
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
        } catch (ResourceAccessException failure) {
            throw unavailable("transport");
        } catch (RuntimeException failure) {
            throw unavailable("malformed_response");
        }
    }

    @Override
    public List<String> loadEligibleInspectors() {
        try {
            JsonNode body = client.get().uri("/internal/v1/users/inspectors/eligible")
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful())
                            throw new InvalidEligibility("dependency_http");
                        var type = response.getHeaders().getContentType();
                        if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type))
                            throw new InvalidEligibility("malformed_response");
                        try {
                            return eligiblePoolReader.readValue(bounded(response.getBody(), 1024 * 1024));
                        } catch (JsonProcessingException malformed) {
                            throw new InvalidEligibility("malformed_response");
                        }
                    });
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
        } catch (ResourceAccessException failure) {
            throw unavailable("transport");
        } catch (RuntimeException failure) {
            throw unavailable("malformed_response");
        }
    }

    private static EligibilityUnavailableException unavailable(String category) {
        // Only bounded, locally defined categories: never pass the exception or remote data to the logger.
        LOG.warn("eligibility_dependency_failure category={}", category);
        return new EligibilityUnavailableException();
    }

    private static byte[] bounded(java.io.InputStream body, int limit) throws java.io.IOException {
        byte[] bytes = body.readNBytes(limit + 1);
        if (bytes.length > limit) throw new InvalidEligibility("transport");
        return bytes;
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
