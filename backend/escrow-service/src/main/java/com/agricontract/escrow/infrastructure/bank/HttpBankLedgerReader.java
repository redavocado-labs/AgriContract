package com.agricontract.escrow.infrastructure.bank;

import com.agricontract.escrow.application.exception.BankLedgerUnavailableException;
import com.agricontract.escrow.application.port.BankLedgerReader;
import com.agricontract.escrow.domain.model.LedgerEntryView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * bank-service getInternalLedgerEntries over HTTP: GET /internal/v1/bank/ledger?contractId=, authenticated with
 * X-Internal-Secret. Anything but a readable 200 is BankLedgerUnavailableException (retryable).
 */
@Component
public class HttpBankLedgerReader implements BankLedgerReader {

    private final HttpClient client;
    private final URI baseUri;
    private final String internalSecret;
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;

    public HttpBankLedgerReader(@Value("${bank-service.url}") String baseUrl,
                                @Value("${service.internal-secret}") String internalSecret,
                                @Value("${bank-service.connect-timeout:PT5S}") Duration connectTimeout,
                                @Value("${bank-service.read-timeout:PT10S}") Duration readTimeout,
                                ObjectMapper objectMapper) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.internalSecret = internalSecret;
        this.readTimeout = readTimeout;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<LedgerEntryView> entriesOf(String contractId) {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("internal/v1/bank/ledger?contractId="
                        + URLEncoder.encode(contractId, StandardCharsets.UTF_8)))
                .timeout(readTimeout)
                .header("X-Internal-Secret", internalSecret)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new BankLedgerUnavailableException("bank ledger read failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BankLedgerUnavailableException("bank ledger read interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new BankLedgerUnavailableException("bank ledger read answered " + response.statusCode(), null);
        }
        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (body == null || !body.isArray()) {
                throw new IllegalArgumentException("not an array");
            }
            List<LedgerEntryView> entries = new ArrayList<>();
            for (JsonNode entry : body) {
                JsonNode milestone = entry.get("milestoneId");
                entries.add(new LedgerEntryView(
                        required(entry, "entryType"),
                        required(entry, "fundType"),
                        milestone == null || milestone.isNull() ? null : milestone.asText().toLowerCase(),
                        new BigDecimal(required(entry, "amount"))));
            }
            return entries;
        } catch (IOException | RuntimeException e) {
            throw new BankLedgerUnavailableException("bank ledger body is unreadable: " + e.getMessage(), e);
        }
    }

    private static String required(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(field + " missing");
        }
        return value.asText();
    }
}
