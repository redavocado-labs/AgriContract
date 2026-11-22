package com.agricontract.inspection.infrastructure.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

/** Inspection-side D60/D62 HTTP client. Non-2xx and malformed responses fail closed. */
public final class FileServiceClient implements com.agricontract.inspection.application.port.ReportFileStore {
    private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Set<String> STATUSES = Set.of("PROCESSING", "READY", "FAILED");
    private final URI base;
    private final String secret;
    private final RestTemplate http;
    private final ObjectMapper mapper = new ObjectMapper();

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
        public Unavailable(String message, Throwable cause) { super(message, cause); }
    }
    public static class NotReady extends RuntimeException {}
    public static class InvalidUpload extends RuntimeException {}

    public FileServiceClient(URI base, String secret, int connectTimeoutMs, int readTimeoutMs) {
        if (base == null || !Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || secret == null || secret.isBlank() || connectTimeoutMs <= 0 || readTimeoutMs <= 0)
            throw new IllegalArgumentException("Invalid file-service configuration");
        this.base = base;
        this.secret = secret;
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.http = new RestTemplate(factory);
        this.http.setErrorHandler(new ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse response) { return false; }
            @Override public void handleError(org.springframework.http.client.ClientHttpResponse response) {}
        });
    }

    public StoredFile storeOnBehalfOf(String actor, String key, String contentType, long length, InputStream body) {
        UUID.fromString(actor);
        if (key == null || !key.matches("[\\x20-\\x7e]{1,255}") || contentType == null || !TYPES.contains(contentType)
                || length < 1 || length > MAX_FILE_BYTES || body == null) throw new IllegalArgumentException("Invalid upload");
        try {
            return http.execute(endpoint("/internal/v1/files"), HttpMethod.POST, request -> {
                request.getHeaders().set("X-Internal-Secret", secret);
                request.getHeaders().set("X-Acting-User-Id", actor);
                request.getHeaders().set("Idempotency-Key", key);
                request.getHeaders().set("Content-Type", contentType);
                request.getHeaders().setContentLength(length);
                byte[] buffer = new byte[8192];
                long remaining = length;
                while (remaining > 0) {
                    int read = body.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new InvalidUpload();
                    if (read == 0) continue;
                    request.getBody().write(buffer, 0, read);
                    remaining -= read;
                }
                if (body.read() != -1) throw new InvalidUpload();
            }, response -> {
                if (response.getStatusCode().value() != 202) throw new Unavailable("File upload failed");
                var json = mapper.readTree(readBounded(response.getBody(), 4096));
                if (json == null || !json.isObject() || json.size() != 2 || !json.path("fileId").isTextual()
                        || !json.path("status").isTextual()) throw new Unavailable("Invalid file-service response");
                String fileId = json.path("fileId").textValue();
                try { UUID.fromString(fileId); } catch (IllegalArgumentException invalid) { throw new Unavailable("Invalid file id"); }
                String status = json.path("status").textValue();
                if (!STATUSES.contains(status)) throw new Unavailable("Invalid file status");
                return new StoredFile(fileId, status);
            });
        } catch (InvalidUpload | Unavailable failure) { throw failure; }
        catch (RuntimeException failure) { throw new Unavailable("File upload unavailable", failure); }
    }

    public byte[] getReady(String fileId) {
        UUID.fromString(fileId);
        try {
            return http.execute(endpoint("/internal/v1/files/" + fileId), HttpMethod.GET,
                    request -> request.getHeaders().set("X-Internal-Secret", secret), response -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) throw new NotReady();
                        if (status != 200) throw new Unavailable("File retrieval failed");
                        long declared = response.getHeaders().getContentLength();
                        if (declared == 0 || declared > MAX_FILE_BYTES) throw new Unavailable("Invalid file length");
                        byte[] bytes = readBounded(response.getBody(), MAX_FILE_BYTES);
                        if (bytes.length == 0 || (declared >= 0 && declared != bytes.length))
                            throw new Unavailable("Incomplete file response");
                        return bytes;
                    });
        } catch (NotReady | Unavailable failure) { throw failure; }
        catch (RuntimeException failure) { throw new Unavailable("File retrieval unavailable", failure); }
    }

    public void confirmAttached(String fileId) {
        UUID.fromString(fileId);
        try {
            http.execute(endpoint("/internal/v1/files/" + fileId + "/confirm-attached"), HttpMethod.POST,
                    request -> {
                        request.getHeaders().set("X-Internal-Secret", secret);
                        request.getHeaders().set("Idempotency-Key", fileId);
                        request.getHeaders().setContentLength(0);
                    }, response -> {
                        if (response.getStatusCode().value() != 204) throw new Unavailable("File attachment confirmation failed");
                        return null;
                    });
        } catch (Unavailable failure) { throw failure; }
        catch (RuntimeException failure) { throw new Unavailable("File attachment confirmation unavailable", failure); }
    }

    private URI endpoint(String path) { return base.resolve(path); }

    private static byte[] readBounded(InputStream stream, int maximum) throws IOException {
        byte[] bytes = stream.readNBytes(maximum + 1);
        if (bytes.length > maximum) throw new IOException("File-service response too large");
        return bytes;
    }
}
