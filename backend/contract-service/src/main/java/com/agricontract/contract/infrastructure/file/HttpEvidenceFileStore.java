package com.agricontract.contract.infrastructure.file;

import com.agricontract.contract.application.exception.FileRejectedException;
import com.agricontract.contract.application.exception.FileServiceFailedException;
import com.agricontract.contract.application.exception.FileServiceUnavailableException;
import com.agricontract.contract.application.port.EvidenceFileStore;
import com.agricontract.contract.domain.model.milestone.EvidenceScanStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * file-service over HTTP (supplementary-services-api.yaml storeOnBehalfOf, confirmAttached). The upload is the raw
 * body streamed through with its Content-Length (D60: no multipart, no buffering of the 10 MiB file in memory).
 */
@Slf4j
@Component
public class HttpEvidenceFileStore implements EvidenceFileStore {
    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final HttpClient client;
    private final URI baseUri;
    private final String internalSecret;
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;

    public HttpEvidenceFileStore(@Value("${file-service.url}") String baseUrl,
                                 @Value("${service.internal-secret}") String internalSecret,
                                 @Value("${file-service.connect-timeout:PT5S}") Duration connectTimeout,
                                 @Value("${file-service.read-timeout:PT60S}") Duration readTimeout,
                                 ObjectMapper objectMapper) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.internalSecret = internalSecret;
        this.readTimeout = readTimeout;
        this.objectMapper = objectMapper;
    }

    @Override
    public StoredFile storeOnBehalfOf(String actingUserId, String idempotencyKey, String contentType,
                                      long contentLength, InputStream body) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("internal/v1/files"))
                .timeout(readTimeout)
                .header("X-Internal-Secret", internalSecret)
                .header("X-Acting-User-Id", actingUserId)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> body),
                        contentLength))
                .build();
        HttpResponse<String> response = send(request, "storeOnBehalfOf");
        int status = response.statusCode();
        if (status == 202) {
            return parseStored(response.body());
        }
        if (status == 400 || status == 413) {
            String code = errorCode(response.body(), status == 413 ? FileRejectedException.FILE_TOO_LARGE
                    : FileRejectedException.VALIDATION_ERROR);
            throw new FileRejectedException(status, code, status == 413 ? "The file is larger than 10 MiB."
                    : "The file was refused: check its type (JPEG, PNG or PDF) and length.");
        }
        throw unexpected("storeOnBehalfOf", status, response.body());
    }

    @Override
    public void confirmAttached(String fileId) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("internal/v1/files/" + fileId
                        + "/confirm-attached"))
                .timeout(readTimeout)
                .header("X-Internal-Secret", internalSecret)
                // One confirmation per file: the same key on every retry.
                .header("Idempotency-Key", UUID.nameUUIDFromBytes(("confirm-attached:" + fileId)
                        .getBytes(StandardCharsets.UTF_8)).toString())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = send(request, "confirmAttached");
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw unexpected("confirmAttached " + fileId, response.statusCode(), response.body());
        }
    }

    /**
     * getFile (supplementary-services-api.yaml, delivered by file-service #274). Until #274 is merged file-service has
     * no such route and every read is a 503 here.
     */
    @Override
    public FileContent read(String fileId) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("internal/v1/files/" + fileId))
                .timeout(readTimeout)
                .header("X-Internal-Secret", internalSecret)
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new FileServiceUnavailableException("file-service getFile failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileServiceUnavailableException("file-service getFile interrupted", e);
        }
        if (response.statusCode() != 200) {
            try (InputStream ignored = response.body()) {
                // only released: the body of a failed read is not logged
            } catch (IOException ignored) {
                // nothing to release
            }
            throw unexpected("getFile " + fileId, response.statusCode(), null);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        return new FileContent(contentType, length, response.body());
    }

    private HttpResponse<String> send(HttpRequest request, String operation) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new FileServiceUnavailableException("file-service " + operation + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileServiceUnavailableException("file-service " + operation + " interrupted", e);
        }
    }

    /**
     * The one classifier of a status outside the operation's contract: a 5xx is file-service being down (503 to the
     * client, retryable with the same key, like a connection failure); anything else is a broken contract that a
     * retry cannot fix (non-retryable, logged as an error).
     */
    static RuntimeException unexpected(String operation, int status, String body) {
        if (status >= 500 && status <= 599) {
            log.warn("event=file_service_unavailable operation={} status={} body={}", operation, status,
                    abbreviate(body));
            return new FileServiceUnavailableException("file-service " + operation + " answered " + status, null);
        }
        log.error("event=file_service_unexpected_status operation={} status={} body={}", operation, status,
                abbreviate(body));
        return new FileServiceFailedException("file-service " + operation + " answered " + status, null);
    }

    private StoredFile parseStored(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            String fileId = json.path("fileId").asText("");
            if (!UUID_TEXT.matcher(fileId).matches()) {
                throw new IllegalStateException("fileId is not a UUID");
            }
            return new StoredFile(fileId.toLowerCase(), EvidenceScanStatus.valueOf(json.path("status").asText()));
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            log.error("event=file_service_store_unreadable body={}", abbreviate(body));
            throw new FileServiceFailedException("file-service answered an unreadable body", e);
        }
    }

    private String errorCode(String body, String fallback) {
        try {
            String code = objectMapper.readTree(body).path("code").asText("");
            return code.isBlank() ? fallback : code;
        } catch (IOException e) {
            return fallback;
        }
    }

    private static String abbreviate(String body) {
        return body == null || body.length() <= 300 ? body : body.substring(0, 300);
    }
}
