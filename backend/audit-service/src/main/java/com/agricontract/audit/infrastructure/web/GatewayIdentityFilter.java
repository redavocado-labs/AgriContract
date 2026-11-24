package com.agricontract.audit.infrastructure.web;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * /api/** (D97 evidence log): only requests the gateway forwarded, carrying exactly one X-Gateway-Secret equal to
 * ours (constant-time), one X-User-Id UUID and one X-User-Role list of known roles. The identity goes to the request
 * attribute {@link #VIEWER}; anything else is 401 with the shared ErrorEnvelope. No Spring Security, as bank-service.
 */
@Component
public class GatewayIdentityFilter extends OncePerRequestFilter {

    public static final String VIEWER = "auditViewer";
    private static final Set<String> ROLES = Set.of("BUYER", "SELLER", "INSPECTOR", "OPERATOR", "ARBITRATOR", "ADMIN");
    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final byte[] secret;
    private final ObjectMapper json;

    public GatewayIdentityFilter(@Value("${gateway.internal-secret}") String secret, ObjectMapper json) {
        if (!StringUtils.hasText(secret)) {
            throw new IllegalStateException("GATEWAY_INTERNAL_SECRET must be set");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    /** Who is reading: the user id and roles the gateway took from the token. */
    public record Viewer(String userId, Set<String> roles) {
        public boolean has(String role) {
            return roles.contains(role);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = single(request, "X-Gateway-Secret");
        String userId = single(request, "X-User-Id");
        String roles = single(request, "X-User-Role");
        if (supplied != null && MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8))
                && userId != null && UUID_TEXT.matcher(userId).matches() && roles != null) {
            Set<String> roleSet = Arrays.stream(roles.split(",", -1)).map(String::trim).collect(Collectors.toSet());
            if (!roleSet.isEmpty() && ROLES.containsAll(roleSet)) {
                request.setAttribute(VIEWER, new Viewer(userId.toLowerCase(), Set.copyOf(roleSet)));
                chain.doFilter(request, response);
                return;
            }
        }
        String correlation = request.getHeader("X-Correlation-Id");
        HttpStatus status = HttpStatus.UNAUTHORIZED;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ErrorEnvelope(Instant.now(), status.value(),
                status.getReasonPhrase(), "UNAUTHORIZED", "Missing or invalid gateway identity",
                request.getRequestURI(), StringUtils.hasText(correlation) ? correlation : UUID.randomUUID().toString()));
    }

    private static String single(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }
}
