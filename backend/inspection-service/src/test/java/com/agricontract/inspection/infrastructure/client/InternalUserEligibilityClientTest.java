package com.agricontract.inspection.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import com.agricontract.inspection.application.exception.EligibilityUnavailableException;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class InternalUserEligibilityClientTest {
    private static final String ID = "1349fbf6-44b6-450c-b01d-4b5abaf7c326";
    private static final String VALID = """
            {"userId":"%s","organizationName":"Inspection organization","email":"private@example.test",
             "role":"INSPECTOR","verificationStatus":"VERIFIED","authorizationExpiresAt":null,
             "lockedUntil":"2099-01-01T00:00:00Z"}
            """.formatted(ID);
    private final ListAppender<ILoggingEvent> logs =
            new ListAppender<>();
    private final Logger logger = (Logger)
            LoggerFactory.getLogger(InternalUserEligibilityClient.class);
    private String expectedCategory;
    private volatile CountDownLatch responseGate;
    private volatile boolean interruptBody;
    private volatile CountDownLatch bodyGate;
    private volatile boolean partialBody;
    private volatile int bodyDelayMs;
    private volatile CountDownLatch trickleGate;
    private volatile CountDownLatch trickleStarted;
    private HttpServer server;
    private InternalUserEligibilityClient client;
    private volatile int status = 200;
    private volatile String response = VALID;
    private volatile String contentType = "application/json";
    private final AtomicReference<String> requestedPath = new AtomicReference<>();
    private final AtomicReference<String> internalSecret = new AtomicReference<>();

    @BeforeEach
    void startUserServiceStub() throws Exception {
        logs.start();
        logger.addAppender(logs);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath.set(exchange.getRequestURI().getPath());
            internalSecret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            awaitResponseGate();
            exchange.sendResponseHeaders(status, bytes.length);
            if (interruptBody) {
                exchange.close();
                return;
            }
            try (var stream = exchange.getResponseBody()) {
                var trickle = trickleGate;
                if (trickle != null) {
                    for (byte value : bytes) {
                        stream.write(value); stream.flush();
                        trickleStarted.countDown();
                        if (trickle.await(75, TimeUnit.MILLISECONDS)) return;
                    }
                    return;
                }
                var gate = bodyGate;
                if (gate != null) {
                    if (partialBody) { stream.write(bytes, 0, Math.min(5, bytes.length)); stream.flush(); }
                    try { gate.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return;
                }
                if (bodyDelayMs > 0) {
                    try { Thread.sleep(bodyDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                stream.write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        server.start();
        client = new InternalUserEligibilityClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-service-secret", 500, 500);
    }

    @Test
    void inspectorEmailUsesVerifiedInternalProfile() {
        response = "{\"userId\":\"" + ID + "\",\"email\":\"inspector@example.com\",\"emailVerified\":true}";
        assertThat(client.loadInspectorEmail(ID)).isEqualTo("inspector@example.com");
        assertThat(requestedPath.get()).isEqualTo("/internal/v1/users/" + ID);
        assertThat(internalSecret.get()).isEqualTo("test-service-secret");
    }

    @Test void nonPositiveTimeoutsAreRejected() {
        assertThatThrownBy(() -> new InternalUserEligibilityClient(RestClient.builder(),
                "http://localhost", "secret", 0, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalUserEligibilityClient(RestClient.builder(),
                "http://localhost", "secret", 500, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void duplicateProfileKeysAreRejected() {
        response = "{\"userId\":\"" + ID + "\",\"email\":\"inspector@example.com\",\"emailVerified\":true,\"emailVerified\":true}";
        expectedCategory = "malformed_response";
        assertThatThrownBy(() -> client.loadInspectorEmail(ID)).isInstanceOf(EligibilityUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"userId\":\"wrong\",\"email\":\"a@example.test\",\"emailVerified\":true}",
            "{\"userId\":\"1349fbf6-44b6-450c-b01d-4b5abaf7c326\",\"email\":\"invalid\",\"emailVerified\":true}",
            "{\"userId\":\"1349fbf6-44b6-450c-b01d-4b5abaf7c326\",\"email\":\"a..b@example.com\",\"emailVerified\":true}",
            "{\"userId\":\"1349fbf6-44b6-450c-b01d-4b5abaf7c326\",\"email\":null,\"emailVerified\":false}"})
    void inspectorEmailRejectsMalformedOrMismatchedProfiles(String profile) {
        response = profile;
        expectedCategory = "malformed_response";
        assertThatThrownBy(() -> client.loadInspectorEmail(ID)).isInstanceOf(EligibilityUnavailableException.class);
    }

    @Test void inspectorEmailTreatsServerFailureAsRetryable() {
        status = 503;
        expectedCategory = "dependency_http";
        assertThatThrownBy(() -> client.loadInspectorEmail(ID)).isInstanceOf(EligibilityUnavailableException.class);
    }

    @Test void inspectorEmailTimeoutIsRetryable() {
        expectedCategory = "transport";
        responseGate = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> client.loadInspectorEmail(ID)).isInstanceOf(EligibilityUnavailableException.class);
        } finally {
            responseGate.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @org.junit.jupiter.api.Timeout(5)
    void stalledInspectorBodyTimesOutAndClientRecovers(boolean partial) {
        response = "{\"userId\":\"" + ID + "\",\"email\":\"inspector@example.com\",\"emailVerified\":true}";
        partialBody = partial;
        bodyGate = new CountDownLatch(1);
        expectedCategory = "transport";
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.loadInspectorEmail(ID)).isInstanceOf(EligibilityUnavailableException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2000);
        bodyGate.countDown();
        bodyGate = null;
        assertThat(client.loadInspectorEmail(ID)).isEqualTo("inspector@example.com");
    }

    @Test @org.junit.jupiter.api.Timeout(5)
    void slowCompleteInspectorBodyWithinDeadlineSucceeds() {
        response = "{\"userId\":\"" + ID + "\",\"email\":\"inspector@example.com\",\"emailVerified\":true}";
        bodyDelayMs = 100;
        assertThat(client.loadInspectorEmail(ID)).isEqualTo("inspector@example.com");
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    @org.junit.jupiter.api.Timeout(5)
    void tricklingBodyCannotExtendTotalDeadline() {
        response = "{\"userId\":\"" + ID + "\",\"email\":\"inspector@example.com\",\"emailVerified\":true}";
        trickleGate = new CountDownLatch(1);
        trickleStarted = new CountDownLatch(1);
        expectedCategory = "transport";
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> client.loadInspectorEmail(ID)).isInstanceOf(EligibilityUnavailableException.class);
            assertThat(trickleStarted.getCount()).isZero();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2000);
        } finally {
            trickleGate.countDown();
            trickleGate = null;
        }
        assertThat(client.loadInspectorEmail(ID)).isEqualTo("inspector@example.com");
    }

    @Test @org.junit.jupiter.api.Timeout(5)
    void oversizedInspectorBodyFailsClosed() {
        response = "x".repeat(100_000);
        expectedCategory = "transport";
        assertThatThrownBy(() -> client.loadInspectorEmail(ID)).isInstanceOf(EligibilityUnavailableException.class);
    }

    @Test
    void poolUsesPrivateServiceEndpointAndMinimalIdentityResponse() {
        response = "[{\"userId\":\"" + ID + "\"}]";
        assertThat(client.loadEligibleInspectors()).containsExactly(ID);
        assertThat(requestedPath.get()).isEqualTo("/internal/v1/users/inspectors/eligible");
        assertThat(internalSecret.get()).isEqualTo("test-service-secret");
    }

    @Test
    void emptyAuthoritativePoolIsValid() {
        response = "[]";
        assertThat(client.loadEligibleInspectors()).isEmpty();
    }

    @Test
    void malformedPoolFailsClosedInsteadOfBecomingEmpty() {
        response = "[{\"userId\":\"not-a-uuid\"}]";
        expectedCategory = "malformed_response";
        assertThatThrownBy(client::loadEligibleInspectors).isInstanceOf(EligibilityUnavailableException.class);
    }

    @Test
    void unavailablePoolIsNotReportedAsEmpty() {
        status = 503;
        expectedCategory = "dependency_http";
        assertThatThrownBy(client::loadEligibleInspectors).isInstanceOf(EligibilityUnavailableException.class);
    }

    @Test
    void duplicatedPoolIdsCannotBiasSelection() {
        response = "[{\"userId\":\"" + ID + "\"},{\"userId\":\"" + ID + "\"}]";
        expectedCategory = "malformed_response";
        assertThatThrownBy(client::loadEligibleInspectors).isInstanceOf(EligibilityUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "not JSON", "[null]", "[{}]", "[] []",
            "[{\"userId\":null}]", "[{\"userId\":\"x\",\"userId\":\"y\"}]"})
    void malformedPoolFailsClosed(String body) {
        response = body;
        expectedCategory = "malformed_response";
        assertThatThrownBy(client::loadEligibleInspectors).isInstanceOf(EligibilityUnavailableException.class);
    }

    @Test
    void poolTimeoutFailsClosed() {
        expectedCategory = "transport";
        responseGate = new CountDownLatch(1);
        try {
            assertThatThrownBy(client::loadEligibleInspectors).isInstanceOf(EligibilityUnavailableException.class);
        } finally {
            responseGate.countDown();
        }
    }

    @AfterEach
    void stopStub() {
        if (responseGate != null) responseGate.countDown();
        if (bodyGate != null) bodyGate.countDown();
        if (client != null) {
            try { client.close(); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        server.stop(0);
        logger.detachAppender(logs);
        logs.stop();
        if (expectedCategory == null) {
            assertThat(logs.list).isEmpty();
        } else {
            assertThat(logs.list).hasSize(1);
            var event = logs.list.getFirst();
            assertThat(event.getFormattedMessage())
                    .isEqualTo("eligibility_dependency_failure category=" + expectedCategory)
                    .doesNotContain(ID, "private@example.test", "test-service-secret", "127.0.0.1", "sentinel");
            assertThat(event.getArgumentArray()).containsExactly(expectedCategory);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getMDCPropertyMap()).isEmpty();
        }
    }

    private void awaitResponseGate() {
        var gate = responseGate;
        if (gate != null) {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void usesFrozenInternalPathAndServiceAuthenticationWithoutApplyingBuyerSellerLock() {
        var profile = client.load(ID);
        assertThat(requestedPath.get()).isEqualTo("/internal/v1/users/" + ID);
        assertThat(internalSecret.get()).isEqualTo("test-service-secret");
        assertThat(profile.isEligibleAt(Instant.parse("2026-10-08T00:00:00Z"))).isTrue();
    }

    @Test
    void parsesAuthoritativeLicenseExpiry() {
        response = VALID.replace("\"authorizationExpiresAt\":null",
                "\"authorizationExpiresAt\":\"2027-06-30T16:59:59.999Z\"");
        assertThat(client.load(ID).authorizationExpiresAt())
                .isEqualTo(Instant.parse("2027-06-30T16:59:59.999Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "not JSON",
            "{\"userId\":\"x\",\"role\":\"INSPECTOR\",\"verificationStatus\":\"VERIFIED\",\"authorizationExpiresAt\":null}"})
    void incompleteOrMismatchedResponsesFailClosed(String body) {
        expectedCategory = body.contains("userId") ? "identity_mismatch" : "malformed_response";
        response = body;
        assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "role", "verificationStatus", "authorizationExpiresAt"})
    void missingRequiredEligibilityInformationFailsClosed(String field) throws Exception {
        expectedCategory = "malformed_response";
        var mapper = new ObjectMapper();
        var body = (ObjectNode) mapper.readTree(VALID);
        body.remove(field);
        response = mapper.writeValueAsString(body);
        assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "false", "\"\"", "\"invalid timestamp\""})
    void malformedExpiryMustNotBecomeUnlimited(String expiry) {
        expectedCategory = "malformed_response";
        response = VALID.replace("\"authorizationExpiresAt\":null", "\"authorizationExpiresAt\":" + expiry);
        assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 401, 403, 404, 500, 503})
    void dependencyHttpErrorsFailClosedWithoutLeakingResponse(int httpStatus) {
        expectedCategory = "dependency_http";
        status = httpStatus;
        response = "sentinel-sensitive-dependency-response";
        assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class)
                .hasMessage("Authoritative inspector eligibility could not be validated");
    }

    @Test
    void connectionFailureFailsClosed() {
        expectedCategory = "transport";
        server.stop(0);
        assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class);
    }

    @Test
    void responseHeaderTimeoutIsTransportFailure() {
        expectedCategory = "transport";
        responseGate = new CountDownLatch(1);
        try {
            assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class)
                    .hasMessage("Authoritative inspector eligibility could not be validated")
                    .hasNoCause();
        } finally {
            responseGate.countDown();
        }
    }

    @Test
    void interruptedResponseBodyIsTransportFailure() {
        expectedCategory = "transport";
        interruptBody = true;
        assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class)
                .hasMessage("Authoritative inspector eligibility could not be validated").hasNoCause();
    }

    @Test
    void malformedSensitiveResponseDoesNotEscapeIntoDiagnostics() {
        expectedCategory = "malformed_response";
        response = "sentinel-private@example.test-test-service-secret";
        assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class)
                .hasMessage("Authoritative inspector eligibility could not be validated").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "invalid-media-type"})
    void invalidContentTypeRemainsFailClosed(String type) {
        expectedCategory = "malformed_response";
        contentType = type;
        assertThatThrownBy(() -> client.load(ID)).isInstanceOf(EligibilityUnavailableException.class)
                .hasMessage("Authoritative inspector eligibility could not be validated").hasNoCause();
    }

    @Test
    void emptySecretIsRejected() {
        assertThatThrownBy(() -> new InternalUserEligibilityClient(RestClient.builder(),
                "http://localhost", " ", 500, 500)).isInstanceOf(IllegalArgumentException.class);
    }
}
