package com.agricontract.bank.infrastructure.web;

import com.agricontract.web.error.ErrorEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

/**
 * Guards /internal/** with the shared service secret (X-Internal-Secret). The gateway never routes /internal/**, so
 * only other services reach it. No Spring Security: one header, compared in constant time.
 */
@Component
public class InternalSecretFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Secret";

    private final byte[] secret;
    private final ObjectMapper json;

    public InternalSecretFilter(@Value("${service.internal-secret}") String secret, ObjectMapper json) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("SERVICE_INTERNAL_SECRET must be set");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), secret)) {
            String correlation = request.getHeader("X-Correlation-Id");
            HttpStatus status = HttpStatus.UNAUTHORIZED;
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), new ErrorEnvelope(Instant.now(), status.value(),
                    status.getReasonPhrase(), "UNAUTHORIZED", "Missing or invalid internal secret",
                    request.getRequestURI(), StringUtils.hasText(correlation) ? correlation : UUID.randomUUID().toString()));
            return;
        }
        chain.doFilter(request, response);
    }
}
