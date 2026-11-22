package com.agricontract.inspection.infrastructure.files;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class FileServiceClientTest {
    HttpServer server;
    FileServiceClient client;
    String file = UUID.randomUUID().toString();
    String empty = UUID.randomUUID().toString();
    String actor = UUID.randomUUID().toString();
    AtomicReference<String> actingUser = new AtomicReference<>();
    AtomicReference<String> idempotency = new AtomicReference<>();
    AtomicReference<String> secret = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/files", exchange -> {
            actingUser.set(exchange.getRequestHeaders().getFirst("X-Acting-User-Id"));
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            secret.set(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("confirm-attached")) { exchange.sendResponseHeaders(204, -1); }
            else if (exchange.getRequestMethod().equals("POST")) {
                exchange.getRequestBody().readAllBytes();
                byte[] response = ("{\"fileId\":\"" + file + "\",\"status\":\"PROCESSING\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(202, response.length); exchange.getResponseBody().write(response);
            } else if (path.endsWith(empty)) {
                exchange.sendResponseHeaders(200, -1);
            } else if (path.endsWith(file)) {
                byte[] response = "verified bytes".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } else exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        client = new FileServiceClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "internal-test", 2000, 3000);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void storeStreamsAndUsesTrustedActingUserAndIdempotencyKey() {
        byte[] bytes = "report".getBytes(StandardCharsets.UTF_8);
        var response = client.storeOnBehalfOf(actor, "Key-A", "application/pdf", bytes.length, new ByteArrayInputStream(bytes));
        assertThat(response.fileId()).isEqualTo(file);
        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(actingUser.get()).isEqualTo(actor);
        assertThat(idempotency.get()).isEqualTo("Key-A");
        assertThat(secret.get()).isEqualTo("internal-test");
    }

    @Test void readsReadyBytesAndTreats404AsNotReady() {
        assertThat(client.getReady(file)).isEqualTo("verified bytes".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> client.getReady(UUID.randomUUID().toString())).isInstanceOf(FileServiceClient.NotReady.class);
        assertThatThrownBy(() -> client.getReady(empty)).isInstanceOf(FileServiceClient.Unavailable.class);
    }

    @Test void confirmsAttachmentWithStableKeyAndRejectsInvalidUploads() {
        client.confirmAttached(file);
        assertThat(idempotency.get()).isEqualTo(file);
        assertThatThrownBy(() -> client.storeOnBehalfOf(actor, "key", "text/html", 1, new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.storeOnBehalfOf(actor, "key", "application/pdf", 10485761, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsBodyLengthMismatch() {
        assertThatThrownBy(() -> client.storeOnBehalfOf(actor, "key", "application/pdf", 3,
                new ByteArrayInputStream(new byte[]{1, 2}))).isInstanceOf(FileServiceClient.InvalidUpload.class);
        assertThatThrownBy(() -> client.storeOnBehalfOf(actor, "key", "application/pdf", 2,
                new ByteArrayInputStream(new byte[]{1, 2, 3}))).isInstanceOf(FileServiceClient.InvalidUpload.class);
    }
}
