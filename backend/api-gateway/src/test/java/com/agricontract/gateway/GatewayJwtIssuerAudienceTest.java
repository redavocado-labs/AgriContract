package com.agricontract.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #533: the gateway's real JWT decoder (keys from the realm JWKS) accepts only tokens whose iss is the
 * agricontract realm and whose aud contains the frontend client. A valid realm signature alone is not enough.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayJwtIssuerAudienceTest {

    private static final String ISSUER = "http://keycloak.test/realms/agricontract";
    private static final String FRONTEND_CLIENT = "agricontract-frontend";
    private static final RSAKey REALM_KEY = generateKey();

    private static final MockWebServer jwks = startJwks();
    private static final MockWebServer downstream = startServer();

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry registry) {
        registry.add("KEYCLOAK_JWK_URI", () -> "http://localhost:" + jwks.getPort() + "/certs");
        registry.add("KEYCLOAK_ISSUER_URI", () -> ISSUER);
        registry.add("KEYCLOAK_AUDIENCE", () -> FRONTEND_CLIENT);
        registry.add("USER_SERVICE_URL", () -> "http://localhost:" + downstream.getPort());
        registry.add("gateway.internal-secret", () -> "it-gateway-secret");
    }

    @Autowired
    private WebTestClient client;

    @BeforeEach
    void setUp() throws InterruptedException {
        while (downstream.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
            // drain requests left over from a previous test
        }
        QueueDispatcher dispatcher = new QueueDispatcher();
        dispatcher.setFailFast(true);
        downstream.setDispatcher(dispatcher);
    }

    @AfterAll
    static void tearDown() throws IOException {
        downstream.shutdown();
        jwks.shutdown();
    }

    @Test
    void frontendToken_isForwarded() throws InterruptedException {
        downstream.enqueue(new MockResponse().setResponseCode(200));

        callProtected(token(ISSUER, List.of("account", FRONTEND_CLIENT), FRONTEND_CLIENT))
                .expectStatus().isOk();

        RecordedRequest received = downstream.takeRequest(2, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.getHeader("X-User-Id")).isEqualTo("user-123");
    }

    @Test
    void tokenForAnotherClientInSameRealm_isRejected() throws InterruptedException {
        // Same realm key and issuer, but issued to the user-service service account: aud has no frontend client.
        callProtected(token(ISSUER, List.of("account", "realm-management"), "user-service"))
                .expectStatus().isUnauthorized();

        assertNotForwarded();
    }

    @Test
    void tokenWithoutAudience_isRejected() throws InterruptedException {
        callProtected(token(ISSUER, List.of(), FRONTEND_CLIENT))
                .expectStatus().isUnauthorized();

        assertNotForwarded();
    }

    @Test
    void tokenFromAnotherIssuer_isRejected() throws InterruptedException {
        callProtected(token("http://keycloak.test/realms/master", List.of(FRONTEND_CLIENT), FRONTEND_CLIENT))
                .expectStatus().isUnauthorized();

        assertNotForwarded();
    }

    private WebTestClient.ResponseSpec callProtected(String token) {
        return client.get().uri("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private static void assertNotForwarded() throws InterruptedException {
        assertThat(downstream.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    private static String token(String issuer, List<String> audience, String authorizedParty) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("user-123")
                .audience(audience)
                .claim("azp", authorizedParty)
                .claim("email", "user@agri.test")
                .claim("realm_access", Map.of("roles", List.of("default-roles-agricontract", "BUYER")))
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(REALM_KEY.getKeyID()).build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(REALM_KEY));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("realm-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockWebServer startJwks() {
        MockWebServer server = startServer();
        String body = new JWKSet(REALM_KEY.toPublicJWK()).toString();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
            }
        });
        return server;
    }

    private static MockWebServer startServer() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return server;
    }
}
