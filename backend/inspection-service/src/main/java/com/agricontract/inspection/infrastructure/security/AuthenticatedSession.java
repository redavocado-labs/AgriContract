package com.agricontract.inspection.infrastructure.security;

import com.agricontract.inspection.application.usecase.ConfirmLevel15Report;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Refuses to derive freshness from a request header outside the trusted gateway filter. */
@Component
public class AuthenticatedSession implements ConfirmLevel15Report.Session {
    @Override public Instant authenticatedAt() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getDetails() instanceof GatewaySession session
                ? session.authenticatedAt() : null;
    }
}
